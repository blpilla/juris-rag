package br.com.jurisrag.retrieval;

import br.com.jurisrag.ingestion.DocumentChunkRepository;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Repository
public class DocumentChunkSearchRepository {
    private static final Pattern CLAUSE = Pattern.compile("(?iu)cl[áa]usula\\s+([0-9]+(?:\\.[0-9]+)*)");
    private static final Pattern CONTRACT = Pattern.compile("(?iu)contrato\\s+(?:n[ºo.]?\\s*)?([0-9]{3})");
    private static final Pattern LINKED_CLAUSE = Pattern.compile("(?iu)altera.{0,100}?cl[áa]usula\\s+([0-9]+(?:\\.[0-9]+)*)");
    private static final Pattern DATE = Pattern.compile("(?iu)(?:vig[eê]ncia|vigente|a partir de|efeitos? em).{0,50}?(\\d{2}/\\d{2}/\\d{4})");
    private final JdbcClient jdbc;

    public DocumentChunkSearchRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<RetrievedChunk> lexical(String tenantId, String query, int limit) {
        return jdbc.sql("""
                        SELECT c.chunk_id, c.document_id, c.tenant_id, d.title, c.page_number, c.position,
                               c.section, c.content,
                               ts_rank_cd(c.search_vector, websearch_to_tsquery('portuguese', :query)) AS rank_score
                        FROM document_chunks c
                        JOIN documents d ON d.document_id = c.document_id AND d.tenant_id = c.tenant_id
                        WHERE c.tenant_id = :tenant
                          AND d.tenant_id = :tenant
                          AND d.status = 'READY'
                          AND c.search_vector @@ websearch_to_tsquery('portuguese', :query)
                        ORDER BY rank_score DESC, c.document_id, c.page_number, c.position
                        LIMIT :limit
                        """)
                .param("tenant", tenantId)
                .param("query", query)
                .param("limit", limit)
                .query((rs, row) -> toRetrieved(
                        rs.getString("chunk_id"), rs.getObject("document_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("title"), rs.getInt("page_number"),
                        rs.getInt("position"), rs.getString("section"), rs.getString("content"),
                        rs.getDouble("rank_score")))
                .list();
    }

    public List<RetrievedChunk> vector(String tenantId, float[] queryVector, int limit) {
        return jdbc.sql("""
                        SELECT c.chunk_id, c.document_id, c.tenant_id, d.title, c.page_number, c.position,
                               c.section, c.content, (1 - (c.embedding <=> CAST(:vector AS vector))) AS rank_score
                        FROM document_chunks c
                        JOIN documents d ON d.document_id = c.document_id AND d.tenant_id = c.tenant_id
                        WHERE c.tenant_id = :tenant
                          AND d.tenant_id = :tenant
                          AND d.status = 'READY'
                        ORDER BY c.embedding <=> CAST(:vector AS vector), c.document_id, c.page_number, c.position
                        LIMIT :limit
                        """)
                .param("tenant", tenantId)
                .param("vector", DocumentChunkRepository.vectorLiteral(queryVector))
                .param("limit", limit)
                .query((rs, row) -> toRetrieved(
                        rs.getString("chunk_id"), rs.getObject("document_id", UUID.class),
                        rs.getString("tenant_id"), rs.getString("title"), rs.getInt("page_number"),
                        rs.getInt("position"), rs.getString("section"), rs.getString("content"),
                        rs.getDouble("rank_score")))
                .list();
    }

    private RetrievedChunk toRetrieved(String chunkId, UUID documentId, String tenantId, String title,
                                       int page, int position, String section, String excerpt, double score) {
        String identityText = (section == null ? "" : section + "\n") + excerpt;
        String clause = first(CLAUSE, section == null ? "" : section);
        if (clause == null) clause = first(CLAUSE, identityText);
        String contract = first(CONTRACT, identityText + "\n" + title);
        boolean amendment = (title + " " + identityText).toLowerCase(java.util.Locale.ROOT)
                .matches("(?s).*(aditivo|termo aditivo|retificação|retificacao).*");
        String linkedContract = amendment ? contract : null;
        String linkedClause = amendment ? first(LINKED_CLAUSE, identityText) : null;
        LocalDate effectiveDate = parseDate(first(DATE, identityText));
        return new RetrievedChunk(chunkId, documentId, tenantId, title, page, position, section,
                excerpt, clause, contract, amendment, linkedContract, linkedClause, effectiveDate, score);
    }

    private String first(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.find() ? matcher.group(1) : null;
    }

    private LocalDate parseDate(String value) {
        if (value == null) return null;
        try {
            String[] parts = value.split("/");
            return LocalDate.of(Integer.parseInt(parts[2]), Integer.parseInt(parts[1]), Integer.parseInt(parts[0]));
        } catch (RuntimeException ignored) {
            return null;
        }
    }
}
