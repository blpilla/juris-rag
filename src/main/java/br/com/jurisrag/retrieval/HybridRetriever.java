package br.com.jurisrag.retrieval;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.ingestion.EmbeddingProvider;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class HybridRetriever {
    private final DocumentChunkSearchRepository repository;
    private final EmbeddingProvider embeddings;
    private final JurisRagProperties properties;
    private final MeterRegistry metrics;

    public HybridRetriever(DocumentChunkSearchRepository repository, EmbeddingProvider embeddings,
                          JurisRagProperties properties, MeterRegistry metrics) {
        this.repository = repository;
        this.embeddings = embeddings;
        this.properties = properties;
        this.metrics = metrics;
    }

    public List<RetrievedChunk> search(String tenantId, String query) {
        Timer.Sample sample = Timer.start(metrics);
        try {
            float[] queryVector = embeddings.embed(List.of(query)).getFirst();
            if (queryVector.length != properties.embedding().dimensions()) {
                throw new IllegalStateException("Dimensão do embedding incompatível com pgvector.");
            }
            List<RetrievedChunk> lexical = repository.lexical(tenantId, query, properties.retrieval().lexicalLimit());
            List<RetrievedChunk> vector = repository.vector(tenantId, queryVector, properties.retrieval().vectorLimit());
            metrics.counter("jurisrag.retrieval.branches", "branch", "lexical").increment();
            metrics.counter("jurisrag.retrieval.branches", "branch", "vector").increment();
            return reciprocalRankFusion(lexical, vector, properties.retrieval().rrfK(),
                    properties.retrieval().contextLimit());
        } finally {
            sample.stop(metrics.timer("jurisrag.retrieval.latency"));
        }
    }

    static List<RetrievedChunk> reciprocalRankFusion(List<RetrievedChunk> lexical, List<RetrievedChunk> vector,
                                                     int rrfK, int limit) {
        Map<String, RankedChunk> fused = new LinkedHashMap<>();
        addRanks(fused, lexical, rrfK);
        addRanks(fused, vector, rrfK);
        return fused.values().stream()
                .sorted(Comparator.comparingDouble(RankedChunk::score).reversed()
                        .thenComparing(item -> item.chunk().chunkId()))
                .limit(limit)
                .map(item -> withScore(item.chunk(), item.score()))
                .toList();
    }

    private static void addRanks(Map<String, RankedChunk> fused, List<RetrievedChunk> list, int rrfK) {
        for (int i = 0; i < list.size(); i++) {
            RetrievedChunk chunk = list.get(i);
            double contribution = 1.0 / (rrfK + i + 1.0);
            RankedChunk old = fused.get(chunk.chunkId());
            fused.put(chunk.chunkId(), new RankedChunk(old == null ? chunk : old.chunk(),
                    (old == null ? 0.0 : old.score()) + contribution));
        }
    }

    private static RetrievedChunk withScore(RetrievedChunk chunk, double score) {
        return new RetrievedChunk(chunk.chunkId(), chunk.documentId(), chunk.tenantId(), chunk.title(), chunk.page(),
                chunk.position(), chunk.section(), chunk.excerpt(), chunk.clauseId(), chunk.contractReference(),
                chunk.amendment(), chunk.linkedContractReference(), chunk.linkedClauseId(), chunk.effectiveDate(), score);
    }

    private record RankedChunk(RetrievedChunk chunk, double score) { }
}
