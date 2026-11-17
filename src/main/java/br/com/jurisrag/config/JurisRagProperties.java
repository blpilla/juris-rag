package br.com.jurisrag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalDate;

@ConfigurationProperties(prefix = "juris-rag")
public record JurisRagProperties(
        LocalDate referenceDate,
        Queue queue,
        Retrieval retrieval,
        Embedding embedding,
        Chunking chunking,
        Aws aws,
        Jwt jwt
) {
    public record Queue(String name, String deadLetterName, int visibilitySeconds, int maxReceiveCount) { }
    public record Retrieval(int lexicalLimit, int vectorLimit, int contextLimit, int rrfK) { }
    public record Embedding(String chatModel, String model, int dimensions, int batchSize, String ollamaBaseUrl) { }
    public record Chunking(int targetCharacters, int overlapCharacters) { }
    public record Aws(String region, String sqsEndpoint) { }
    public record Jwt(String issuer, String audience, String publicKeyPath) { }
}
