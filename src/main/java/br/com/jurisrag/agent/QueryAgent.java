package br.com.jurisrag.agent;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.retrieval.Citation;
import br.com.jurisrag.retrieval.HybridRetriever;
import br.com.jurisrag.retrieval.RetrievedChunk;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphStateException;
import org.bsc.langgraph4j.StateGraph;
import org.springframework.stereotype.Service;

import java.text.Normalizer;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static org.bsc.langgraph4j.StateGraph.END;
import static org.bsc.langgraph4j.StateGraph.START;

@Service
public class QueryAgent {
    private static final String INSUFFICIENT = "Não localizei evidências suficientes no acervo consultado para responder com segurança.";
    private final HybridRetriever retriever;
    private final AgentModel model;
    private final EvidencePolicy evidencePolicy;
    private final JurisRagProperties properties;
    private final MeterRegistry metrics;
    private final CompiledGraph<ConsultationState> graph;

    public QueryAgent(HybridRetriever retriever, AgentModel model, EvidencePolicy evidencePolicy,
                      JurisRagProperties properties, MeterRegistry metrics) {
        this.retriever = retriever;
        this.model = model;
        this.evidencePolicy = evidencePolicy;
        this.properties = properties;
        this.metrics = metrics;
        this.graph = buildGraph();
    }

    public QueryResponse query(String tenantId, String question) {
        Timer.Sample sample = Timer.start(metrics);
        UUID queryId = UUID.randomUUID();
        try {
            ConsultationState state = graph.invoke(Map.of(
                    ConsultationState.QUERY_ID, queryId.toString(),
                    ConsultationState.TENANT, tenantId,
                    ConsultationState.ORIGINAL, question,
                    ConsultationState.CURRENT, question,
                    ConsultationState.ATTEMPT, 0,
                    ConsultationState.REWRITTEN, false
            )).orElseThrow(() -> new IllegalStateException("O grafo não retornou um estado final."));
            Set<String> approved = Set.copyOf(state.approvedIds());
            Map<String, RetrievedChunk> authorized = state.evidence().stream()
                    .filter(chunk -> approved.contains(chunk.chunkId()))
                    .filter(chunk -> tenantId.equals(chunk.tenantId()))
                    .collect(Collectors.toMap(RetrievedChunk::chunkId, chunk -> chunk, (left, right) -> left));
            List<Citation> citations = state.citationIds().stream()
                    .map(authorized::get)
                    .filter(java.util.Objects::nonNull)
                    .map(chunk -> new Citation(chunk.documentId(), chunk.title(), chunk.page(), chunk.excerpt()))
                    .toList();
            if (!"ANSWERED".equals(state.string(ConsultationState.STATUS)) || citations.isEmpty()) {
                metrics.counter("jurisrag.agent.abstentions").increment();
                return new QueryResponse("INSUFFICIENT_EVIDENCE", INSUFFICIENT, List.of(), state.attempt(), state.rewritten());
            }
            return new QueryResponse("ANSWERED", state.string(ConsultationState.ANSWER), citations,
                    state.attempt(), state.rewritten());
        } finally {
            sample.stop(metrics.timer("jurisrag.agent.query.latency"));
        }
    }

    private CompiledGraph<ConsultationState> buildGraph() {
        try {
            return new StateGraph<>(ConsultationState.SCHEMA, ConsultationState::new)
                    .addNode("retrieve", (state, config) -> CompletableFuture.completedFuture(retrieve(state)))
                    .addNode("assess", (state, config) -> CompletableFuture.completedFuture(assess(state)))
                    .addNode("rewrite", (state, config) -> CompletableFuture.completedFuture(rewrite(state)))
                    .addNode("answer", (state, config) -> CompletableFuture.completedFuture(answer(state)))
                    .addNode("abstain", (state, config) -> CompletableFuture.completedFuture(abstain(state)))
                    .addEdge(START, "retrieve")
                    .addEdge("retrieve", "assess")
                    .addConditionalEdges("assess", (state, config) -> CompletableFuture.completedFuture(assessmentRoute(state)),
                            Map.of("answer", "answer", "rewrite", "rewrite", "abstain", "abstain"))
                    .addConditionalEdges("rewrite", (state, config) -> CompletableFuture.completedFuture(rewriteRoute(state)),
                            Map.of("retrieve", "retrieve", "abstain", "abstain"))
                    .addEdge("answer", END)
                    .addEdge("abstain", END)
                    .compile();
        } catch (GraphStateException error) {
            throw new IllegalStateException("Não foi possível compilar o grafo de consulta.", error);
        }
    }

    private Map<String, Object> retrieve(ConsultationState state) {
        int attempt = state.attempt() + 1;
        List<RetrievedChunk> evidence = retriever.search(state.string(ConsultationState.TENANT),
                state.string(ConsultationState.CURRENT));
        Map<Integer, List<String>> history = new HashMap<>(state.history());
        List<String> ids = evidence.stream().map(RetrievedChunk::chunkId).toList();
        history.put(attempt, ids);
        metrics.counter("jurisrag.agent.attempts", "attempt", Integer.toString(attempt)).increment();
        logRetrieval(state.string(ConsultationState.QUERY_ID), attempt, ids);
        return Map.of(ConsultationState.ATTEMPT, attempt, ConsultationState.EVIDENCE, evidence,
                ConsultationState.HISTORY, history);
    }

    private Map<String, Object> assess(ConsultationState state) {
        EvidencePolicy.PolicyCheck policy = evidencePolicy.check(state.string(ConsultationState.ORIGINAL),
                state.evidence(), properties.referenceDate());
        AgentModel.Evaluation evaluation = model.evaluate(state.string(ConsultationState.CURRENT),
                state.evidence(), policy.gap(), properties.referenceDate());
        Set<String> authorized = state.evidence().stream().map(RetrievedChunk::chunkId).collect(Collectors.toSet());
        boolean validIds = !evaluation.evidenceIds().isEmpty() && authorized.containsAll(evaluation.evidenceIds());
        boolean includesRequired = evaluation.evidenceIds().containsAll(policy.requiredEvidenceIds());
        boolean sufficient = evaluation.sufficient() && validIds && includesRequired && !policy.hasGap();
        String gap = policy.hasGap() ? policy.gap() : clean(evaluation.gap());
        List<String> approved = sufficient ? evaluation.evidenceIds().stream().distinct().toList() : List.of();
        if (!sufficient && gap.isBlank()) gap = "Os trechos recuperados não sustentam uma conclusão verificável.";
        return Map.of(ConsultationState.STATUS, sufficient ? "ASSESSMENT_SUFFICIENT" : "ASSESSMENT_INSUFFICIENT",
                ConsultationState.GAP, gap, ConsultationState.APPROVED_IDS, approved,
                ConsultationState.REQUIRED_IDS, policy.requiredEvidenceIds());
    }

    private String assessmentRoute(ConsultationState state) {
        if ("ASSESSMENT_SUFFICIENT".equals(state.string(ConsultationState.STATUS))) return "answer";
        return state.attempt() < 2 ? "rewrite" : "abstain";
    }

    private Map<String, Object> rewrite(ConsultationState state) {
        String newQuery = model.rewrite(state.string(ConsultationState.ORIGINAL), state.string(ConsultationState.GAP),
                state.evidence());
        boolean changed = newQuery != null && !newQuery.isBlank() && !normalize(newQuery).equals(normalize(state.string(ConsultationState.ORIGINAL)));
        if (!changed) return Map.of(ConsultationState.STATUS, "REWRITE_INVALID", ConsultationState.CURRENT, "");
        metrics.counter("jurisrag.agent.reformulations").increment();
        return Map.of(ConsultationState.STATUS, "REWRITE_READY", ConsultationState.CURRENT, newQuery,
                ConsultationState.REWRITTEN, true);
    }

    private String rewriteRoute(ConsultationState state) {
        return "REWRITE_READY".equals(state.string(ConsultationState.STATUS)) && state.attempt() < 2
                ? "retrieve" : "abstain";
    }

    private Map<String, Object> answer(ConsultationState state) {
        List<RetrievedChunk> approvedEvidence = state.evidence().stream()
                .filter(chunk -> state.approvedIds().contains(chunk.chunkId())).toList();
        AgentModel.AnswerDraft draft = model.answer(state.string(ConsultationState.ORIGINAL), approvedEvidence);
        Set<String> allowed = Set.copyOf(state.approvedIds());
        boolean valid = draft.answer() != null && !draft.answer().isBlank() && !draft.evidenceIds().isEmpty()
                && allowed.containsAll(draft.evidenceIds())
                && draft.evidenceIds().containsAll(state.requiredIds());
        if (!valid) return Map.of(ConsultationState.STATUS, "INVALID_CITATIONS", ConsultationState.ANSWER, INSUFFICIENT,
                ConsultationState.CITATION_IDS, List.of());
        return Map.of(ConsultationState.STATUS, "ANSWERED", ConsultationState.ANSWER, draft.answer(),
                ConsultationState.CITATION_IDS, draft.evidenceIds().stream().distinct().toList());
    }

    private Map<String, Object> abstain(ConsultationState state) {
        return Map.of(ConsultationState.STATUS, "INSUFFICIENT_EVIDENCE", ConsultationState.ANSWER, INSUFFICIENT,
                ConsultationState.CITATION_IDS, List.of());
    }

    private void logRetrieval(String queryId, int attempt, List<String> ids) {
        org.slf4j.LoggerFactory.getLogger(QueryAgent.class).info(
                "agent.retrieval queryId={} attempt={} retrievedChunkIds={}", queryId, attempt, ids);
    }

    private String clean(String value) { return value == null ? "" : value.strip(); }

    private String normalize(String value) {
        return Normalizer.normalize(value == null ? "" : value, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT).replaceAll("[^\\p{L}0-9]+", " ").strip();
    }
}
