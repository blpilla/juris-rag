package br.com.jurisrag.documents;

import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;

@Repository
public class DocumentRepository {
    private static final RowMapper<DocumentRecord> ROW_MAPPER = (rs, row) -> new DocumentRecord(
            rs.getObject("document_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("title"),
            rs.getString("sha256").trim(),
            rs.getBytes("pdf_bytes"),
            rs.getString("status"),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));
    private static final RowMapper<DocumentRecord> METADATA_ROW_MAPPER = (rs, row) -> new DocumentRecord(
            rs.getObject("document_id", UUID.class),
            rs.getString("tenant_id"),
            rs.getString("title"),
            rs.getString("sha256").trim(),
            null,
            rs.getString("status"),
            rs.getObject("updated_at", java.time.OffsetDateTime.class));

    private final JdbcClient jdbc;

    public DocumentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public DocumentRecord createOrFind(String tenantId, String title, String sha256, byte[] pdfBytes) {
        Optional<DocumentRecord> existing = findByTenantAndHash(tenantId, sha256);
        if (existing.isPresent()) {
            return existing.get();
        }

        UUID documentId = UUID.nameUUIDFromBytes((tenantId + ":" + sha256).getBytes(StandardCharsets.UTF_8));
        int inserted = jdbc.sql("""
                        INSERT INTO documents(document_id, tenant_id, title, sha256, pdf_bytes, status)
                        VALUES (:id, :tenant, :title, :sha, :bytes, 'PENDING')
                        ON CONFLICT (tenant_id, sha256) DO NOTHING
                        """)
                .param("id", documentId)
                .param("tenant", tenantId)
                .param("title", title)
                .param("sha", sha256)
                .param("bytes", pdfBytes)
                .update();
        if (inserted == 1) {
            return findByTenantAndHash(tenantId, sha256).orElseThrow();
        }
        return findByTenantAndHash(tenantId, sha256).orElseThrow();
    }

    public Optional<DocumentRecord> findByTenantAndHash(String tenantId, String sha256) {
        return jdbc.sql("SELECT document_id, tenant_id, title, sha256, status, updated_at " +
                        "FROM documents WHERE tenant_id = :tenant AND sha256 = :sha")
                .param("tenant", tenantId)
                .param("sha", sha256)
                .query(METADATA_ROW_MAPPER)
                .optional();
    }

    public Optional<DocumentRecord> findByTenantAndId(String tenantId, UUID documentId) {
        return jdbc.sql("SELECT document_id, tenant_id, title, sha256, status, updated_at " +
                        "FROM documents WHERE tenant_id = :tenant AND document_id = :id")
                .param("tenant", tenantId)
                .param("id", documentId)
                .query(METADATA_ROW_MAPPER)
                .optional();
    }

    public Optional<DocumentRecord> findById(UUID documentId) {
        return jdbc.sql("SELECT document_id, tenant_id, title, sha256, pdf_bytes, status, updated_at " +
                        "FROM documents WHERE document_id = :id")
                .param("id", documentId)
                .query(ROW_MAPPER)
                .optional();
    }
}
