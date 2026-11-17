package br.com.jurisrag.config;

import br.com.jurisrag.agent.AgentModel;
import br.com.jurisrag.agent.ModelMetrics;
import br.com.jurisrag.agent.OllamaAgentModel;
import br.com.jurisrag.ingestion.DeterministicEmbeddingProvider;
import br.com.jurisrag.ingestion.EmbeddingProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.ollama.OllamaChatModel;
import dev.langchain4j.model.ollama.OllamaEmbeddingModel;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.util.List;

@Configuration
public class ModelConfiguration {
    @Bean
    @Profile("deterministic")
    EmbeddingProvider deterministicEmbeddingProvider(JurisRagProperties properties) {
        return new DeterministicEmbeddingProvider(properties.embedding().dimensions());
    }

    @Bean
    @Profile("!deterministic")
    ChatModel ollamaChatModel(JurisRagProperties properties) {
        return OllamaChatModel.builder().baseUrl(properties.embedding().ollamaBaseUrl())
                .modelName(properties.embedding().chatModel()).temperature(0.0).build();
    }

    @Bean
    @Profile("!deterministic")
    EmbeddingModel ollamaEmbeddingModel(JurisRagProperties properties) {
        return OllamaEmbeddingModel.builder().baseUrl(properties.embedding().ollamaBaseUrl())
                .modelName(properties.embedding().model()).build();
    }

    @Bean
    @Profile("!deterministic")
    AgentModel ollamaAgentModel(ChatModel chatModel, ObjectMapper objectMapper, MeterRegistry metrics,
                                ModelMetrics tokenMetrics) {
        return new OllamaAgentModel(chatModel, objectMapper, metrics, tokenMetrics);
    }

    @Bean
    @Profile("!deterministic")
    EmbeddingProvider langChainEmbeddingProvider(EmbeddingModel model, JurisRagProperties properties) {
        return new EmbeddingProvider() {
            @Override
            public List<float[]> embed(List<String> texts) {
                return model.embedAll(texts.stream().map(TextSegment::from).toList())
                        .content().stream().map(dev.langchain4j.data.embedding.Embedding::vector).toList();
            }
            @Override public int dimensions() { return properties.embedding().dimensions(); }
            @Override public String modelName() { return properties.embedding().model(); }
        };
    }
}
