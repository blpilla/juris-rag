package br.com.jurisrag.ingestion;

import br.com.jurisrag.config.JurisRagProperties;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class TextChunkerTest {
    @Test
    void preservesPhysicalPageNumbersAndClauseBoundaries() {
        TextChunker chunker = new TextChunker(properties(1000, 100));

        List<TextChunk> chunks = chunker.split(List.of(
                new PageText(2, "Cláusula 12.1 — Resilição\nNotificação prévia de 90 dias."),
                new PageText(3, "Cláusula 13 — Pagamento\nPagamento em 30 dias.")));

        assertThat(chunks).extracting(TextChunk::pageNumber).containsExactly(2, 3);
        assertThat(chunks).extracting(TextChunk::section).contains("Cláusula 12.1 — Resilição", "Cláusula 13 — Pagamento");
        assertThat(chunks).extracting(TextChunk::position).containsExactly(0, 0);
    }

    @Test
    void splitsLongSectionsWithOverlapWithoutChangingPagePositionIdentity() {
        TextChunker chunker = new TextChunker(properties(80, 20));
        String text = "Cláusula 4.2 — Pagamento\n" + "pagamento da fatura em trinta dias corridos. ".repeat(8);

        List<TextChunk> chunks = chunker.split(List.of(new PageText(1, text)));

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks).allSatisfy(chunk -> {
            assertThat(chunk.pageNumber()).isEqualTo(1);
            assertThat(chunk.section()).isEqualTo("Cláusula 4.2 — Pagamento");
        });
        assertThat(chunks).extracting(TextChunk::position)
                .containsExactlyElementsOf(IntStream.range(0, chunks.size()).boxed().toList());
    }

    private JurisRagProperties properties(int targetCharacters, int overlapCharacters) {
        return new JurisRagProperties(LocalDate.of(2026, 11, 1),
                new JurisRagProperties.Queue("q", "dlq", 600, 5),
                new JurisRagProperties.Retrieval(10, 10, 5, 60),
                new JurisRagProperties.Embedding("chat", "embedding", 768, 32, "http://localhost"),
                new JurisRagProperties.Chunking(targetCharacters, overlapCharacters),
                new JurisRagProperties.Aws("us-east-1", ""),
                new JurisRagProperties.Jwt("issuer", "audience", ""));
    }
}
