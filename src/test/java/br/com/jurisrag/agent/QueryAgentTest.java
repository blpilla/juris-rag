package br.com.jurisrag.agent;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.retrieval.HybridRetriever;
import br.com.jurisrag.retrieval.RetrievedChunk;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class QueryAgentTest {
    private static final String TENANT = "energia-demo";
    private final RetrievedChunk payment = chunk("payment", "Contrato 001 — Fornecimento", 1,
            "O prazo para pagamento da fatura será de 30 dias corridos após o recebimento da fatura.",
            "4.2", "001", false, null, null, null);
    private final RetrievedChunk original = chunk("base", "Contrato 001 — Fornecimento", 2,
            "Contrato 001. Qualquer das partes poderá resilir unilateralmente sem justa causa, mediante notificação prévia com prazo mínimo de 90 dias contados do recebimento.",
            "12.1", "001", false, null, null, null);
    private final RetrievedChunk amendment = chunk("amendment", "Aditivo 01 — Contrato 001", 1,
            "Este aditivo altera a Cláusula 12.1 do Contrato 001. O prazo de notificação passa de 90 para 60 dias corridos. Vigência a partir de 01/10/2026.",
            "1.1", "001", true, "001", "12.1", LocalDate.of(2026, 10, 1));

    @Test
    void respondsWithAValidatedCitationForDirectEvidence() {
        HybridRetriever retriever = retriever((query, call) -> List.of(payment));
        QueryAgent agent = agent(retriever, new DeterministicAgentModel());

        QueryResponse response = agent.query(TENANT, "Qual o prazo de pagamento da fatura do Contrato 001?");

        assertThat(response.status()).isEqualTo("ANSWERED");
        assertThat(response.attempts()).isEqualTo(1);
        assertThat(response.rewritten()).isFalse();
        assertThat(response.answer()).contains("30 dias corridos");
        assertThat(response.citations()).singleElement().satisfies(citation -> {
            assertThat(citation.documentId()).isEqualTo(payment.documentId());
            assertThat(citation.page()).isEqualTo(1);
            assertThat(citation.excerpt()).contains("30 dias corridos");
        });
    }

    @Test
    void reformulatesOnceAndCitesTheContractAndEffectiveAmendment() {
        HybridRetriever retriever = retriever((query, call) -> call == 1 ? List.of(original) : List.of(original, amendment));
        QueryAgent agent = agent(retriever, new DeterministicAgentModel());

        QueryResponse response = agent.query(TENANT,
                "Com quanta antecedência precisamos avisar para encerrar o Contrato 001 sem motivo?");

        assertThat(response.status()).isEqualTo("ANSWERED");
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.rewritten()).isTrue();
        assertThat(response.answer()).contains("60 dias", "90 dias");
        assertThat(response.citations()).extracting(citation -> citation.documentId())
                .containsExactlyInAnyOrder(original.documentId(), amendment.documentId());
        assertThat(response.citations()).extracting(citation -> citation.page()).contains(1, 2);
    }

    @Test
    void abstainsAfterSecondSearchWhenEvidenceStillDoesNotSupportTheQuestion() {
        HybridRetriever retriever = retriever((query, call) -> List.of(payment, original));
        QueryAgent agent = agent(retriever, new DeterministicAgentModel());

        QueryResponse response = agent.query(TENANT, "Qual a multa por vazamento de dados no Contrato 001?");

        assertThat(response.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.attempts()).isEqualTo(2);
        assertThat(response.rewritten()).isTrue();
        assertThat(response.citations()).isEmpty();
    }

    @Test
    void rejectsModelCitationIdsThatWereNotAuthorized() {
        HybridRetriever retriever = retriever((query, call) -> List.of(payment));
        AgentModel maliciousOutput = new AgentModel() {
            @Override
            public Evaluation evaluate(String question, List<RetrievedChunk> evidence, String gap, LocalDate date) {
                return new Evaluation(true, List.of(payment.chunkId()), "", "");
            }
            @Override public String rewrite(String question, String gap, List<RetrievedChunk> evidence) { return question + " prazo"; }
            @Override
            public AnswerDraft answer(String question, List<RetrievedChunk> evidence) {
                return new AnswerDraft("Resposta sem procedência", List.of("id-inventado"));
            }
        };

        QueryResponse response = agent.query(TENANT, "Qual o prazo para pagamento?");

        assertThat(response.status()).isEqualTo("INSUFFICIENT_EVIDENCE");
        assertThat(response.answer()).contains("evidências suficientes");
        assertThat(response.citations()).isEmpty();
    }

    private QueryAgent agent(HybridRetriever retriever, AgentModel model) {
        JurisRagProperties properties = new JurisRagProperties(
                LocalDate.of(2026, 11, 1),
                new JurisRagProperties.Queue("ingestion", "ingestion-dlq", 600, 5),
                new JurisRagProperties.Retrieval(10, 10, 5, 60),
                new JurisRagProperties.Embedding("chat", "embedding", 768, 32, "http://localhost:11434"),
                new JurisRagProperties.Chunking(1200, 180),
                new JurisRagProperties.Aws("us-east-1", "http://localhost:9324"),
                new JurisRagProperties.Jwt("test-issuer", "test-audience", ""));
        return new QueryAgent(retriever, model, new EvidencePolicy(), properties, new SimpleMeterRegistry());
    }

    private HybridRetriever retriever(SearchPlan plan) {
        HybridRetriever retriever = mock(HybridRetriever.class);
        AtomicInteger calls = new AtomicInteger();
        when(retriever.search(anyString(), anyString())).thenAnswer(invocation ->
                plan.search(invocation.getArgument(1), calls.incrementAndGet()));
        return retriever;
    }

    @FunctionalInterface
    private interface SearchPlan {
        List<RetrievedChunk> search(String query, int call);
    }

    private RetrievedChunk chunk(String id, String title, int page, String excerpt, String clause,
                                 String contract, boolean isAmendment, String linkedContract,
                                 String linkedClause, LocalDate effectiveDate) {
        return new RetrievedChunk(id, UUID.nameUUIDFromBytes(id.getBytes()), TENANT, title, page, 0,
                clause == null ? null : "Cláusula " + clause, excerpt, clause, contract, isAmendment,
                linkedContract, linkedClause, effectiveDate, 1.0);
    }
}
