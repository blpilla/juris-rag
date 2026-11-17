package br.com.jurisrag.ingestion;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.documents.DocumentRecord;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Repository
public class DocumentChunkRepository {
    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;
    private final JurisRagProperties properties;
    private final EmbeddingProvider embeddings;

    public DocumentChunkRepository(JdbcClient jdbc, TransactionTemplate transactionTemplate,
                                   JurisRagProperties properties, EmbeddingProvider embeddings) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
        this.embeddings = embeddings;
    }

    public boolean publishReady(DocumentRecord document, List<TextChunk> chunks, List<float[]> vectors) {
        if (chunks.size() != vectors.size()) {
            throw new IllegalArgumentException("Cada trecho precisa de um embedding.");
        }
        Boolean committed = transactionTemplate.execute(status -> {
            String currentStatus = jdbc.sql("SELECT status FROM documents WHERE document_id = :id FOR UPDATE")
                    .param("id", document.documentId())
                    .query(String.class)
                    .optional()
                    .orElse(null);
            if (!"PENDING".equals(currentStatus)) {
                return false;
            }
            for (int i = 0; i < chunks.size(); i++) {
                TextChunk chunk = chunks.get(i);
                float[] vector = vectors.get(i);
                if (vector.length != properties.embedding().dimensions()) {
                    throw new IllegalStateException("Dimensão do embedding diferente da configuração do pgvector.");
                }
                jdbc.sql("""
                                INSERT INTO document_chunks(chunk_id, tenant_id, document_id, page_number, position,
                                  section, content, embedding, embedding_model)
                                VALUES (:chunkId, :tenant, :document, :page, :position, :section, :content,
                                  CAST(:embedding AS vector), :model)
                                ON CONFLICT (document_id, page_number, position) DO NOTHING
                                """)
                        .param("chunkId", chunkId(document.tenantId(), document.documentId(), chunk))
                        .param("tenant", document.tenantId())
                        .param("document", document.documentId())
                        .param("page", chunk.pageNumber())
                        .param("position", chunk.position())
                        .param("section", chunk.section())
                        .param("content", chunk.content())
                        .param("embedding", vectorLiteral(vector))
                        .param("model", embeddings.modelName())
                        .update();
            }
            jdbc.sql("UPDATE documents SET status = 'READY', failure_code = NULL, updated_at = now() " +
                            "WHERE document_id = :id AND status = 'PENDING'")
                    .param("id", document.documentId())
                    .update();
            return true;
        });
        return Boolean.TRUE.equals(committed);
    }

    public void fail(UUID documentId, String failureCode) {
        jdbc.sql("UPDATE documents SET status = 'FAILED', failure_code = :code, updated_at = now() " +
                        "WHERE document_id = :id AND status = 'PENDING'")
                .param("id", documentId)
                .param("code", failureCode)
                .update();
    }

    private String chunkId(String tenantId, UUID documentId, TextChunk chunk) {
        String identity = tenantId + ":" + documentId + ":" + chunk.pageNumber() + ":" + chunk.position();
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception error) {
            throw new IllegalStateException("SHA-256 indisponível.", error);
        }
    }

    public static String vectorLiteral(float[] vector) {
        StringBuilder literal = new StringBuilder("[");
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) literal.append(',');
            literal.append(Float.toString(vector[i]));
        }
        return literal.append(']').toString();
    }

}
