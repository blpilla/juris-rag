package br.com.jurisrag.ingestion;

import br.com.jurisrag.config.JurisRagProperties;
import br.com.jurisrag.documents.DocumentRecord;
import br.com.jurisrag.documents.DocumentRepository;
import br.com.jurisrag.retrieval.DocumentChunkSearchRepository;
import br.com.jurisrag.retrieval.RetrievedChunk;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.Executors;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers(disabledWithoutDocker = true)
class DocumentChunkRepositoryIntegrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("pgvector/pgvector:0.8.7-pg18"))
            .withDatabaseName("jurisrag")
            .withUsername("jurisrag")
            .withPassword("jurisrag-test");

    private static DataSource dataSource;
    private static JdbcClient jdbc;
    private static TransactionTemplate transactions;
    private static DocumentRepository documents;
    private static DocumentChunkRepository chunks;
    private static DocumentChunkSearchRepository search;
    private static DeterministicEmbeddingProvider embeddings;

    @BeforeAll
    static void setUpDatabase() {
        DriverManagerDataSource source = new DriverManagerDataSource();
        source.setDriverClassName("org.postgresql.Driver");
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        dataSource = source;

        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = JdbcClient.create(dataSource);
        transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        documents = new DocumentRepository(jdbc);
        embeddings = new DeterministicEmbeddingProvider(768);
        JurisRagProperties properties = properties();
        chunks = new DocumentChunkRepository(jdbc, transactions, properties, embeddings);
        search = new DocumentChunkSearchRepository(jdbc);
    }

    @BeforeEach
    void clearData() {
        jdbc.sql("TRUNCATE TABLE document_chunks, documents CASCADE").update();
    }

    @Test
    void concurrentRedeliveryIsIdempotentAndBothSearchBranchesStayWithinTenant() throws Exception {
        DocumentRecord tenantA = createDocument("energia-demo", "Contrato 001", "%PDF-A");
        assertThat(createDocument("energia-demo", "Título reenviado", "%PDF-A").documentId())
                .isEqualTo(tenantA.documentId());
        DocumentRecord tenantB = createDocument("energia-outro", "Contrato 001", "%PDF-B");
        TextChunk clauseA = new TextChunk(1, 0, "Cláusula 4.2 — Pagamento",
                "O prazo para pagamento da fatura será de 30 dias corridos após o recebimento.");
        TextChunk clauseB = new TextChunk(1, 0, "Cláusula 4.2 — Pagamento",
                "O prazo para pagamento da fatura será de 7 dias úteis após o recebimento.");

        var pool = Executors.newFixedThreadPool(2);
        try {
            var work = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(ignored -> pool.submit(() -> chunks.publishReady(
                            tenantA, List.of(clauseA), embeddings.embed(List.of(clauseA.content())))))
                    .toList();
            assertThat(work.stream().map(future -> {
                try { return future.get(); }
                catch (Exception error) { throw new IllegalStateException(error); }
            }).toList()).containsExactlyInAnyOrder(true, false);
        } finally {
            pool.shutdownNow();
        }
        assertThat(chunks.publishReady(tenantB, List.of(clauseB), embeddings.embed(List.of(clauseB.content())))).isTrue();
        assertThat(jdbc.sql("SELECT count(*) FROM document_chunks WHERE document_id = :id")
                .param("id", tenantA.documentId()).query(Long.class).single()).isEqualTo(1L);
        assertThat(documents.findByTenantAndId("energia-outro", tenantA.documentId())).isEmpty();

        List<RetrievedChunk> lexicalA = search.lexical("energia-demo", "pagamento fatura", 10);
        List<RetrievedChunk> vectorA = search.vector("energia-demo", embeddings.embed(List.of("pagamento fatura")).getFirst(), 10);
        List<RetrievedChunk> lexicalB = search.lexical("energia-outro", "pagamento fatura", 10);
        List<RetrievedChunk> vectorB = search.vector("energia-outro", embeddings.embed(List.of("pagamento fatura")).getFirst(), 10);

        assertThat(lexicalA).extracting(RetrievedChunk::documentId).containsExactly(tenantA.documentId());
        assertThat(vectorA).extracting(RetrievedChunk::documentId).containsExactly(tenantA.documentId());
        assertThat(lexicalB).extracting(RetrievedChunk::documentId).containsExactly(tenantB.documentId());
        assertThat(vectorB).extracting(RetrievedChunk::documentId).containsExactly(tenantB.documentId());
    }

    private DocumentRecord createDocument(String tenant, String title, String pdfContent) {
        byte[] bytes = pdfContent.getBytes(StandardCharsets.UTF_8);
        String hash;
        try {
            hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
        return transactions.execute(status -> documents.createOrFind(tenant, title, hash, bytes));
    }

    private static JurisRagProperties properties() {
        return new JurisRagProperties(LocalDate.of(2026, 11, 1),
                new JurisRagProperties.Queue("ingestion", "ingestion-dlq", 600, 5),
                new JurisRagProperties.Retrieval(10, 10, 5, 60),
                new JurisRagProperties.Embedding("chat", "deterministic", 768, 32, "http://localhost"),
                new JurisRagProperties.Chunking(1200, 180),
                new JurisRagProperties.Aws("us-east-1", ""),
                new JurisRagProperties.Jwt("issuer", "audience", ""));
    }
}
