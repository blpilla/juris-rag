package br.com.jurisrag.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;

import java.net.URI;

@Configuration
public class InfrastructureConfiguration {
    @Bean
    SqsClient sqsClient(JurisRagProperties properties) {
        var builder = SqsClient.builder()
                .region(Region.of(properties.aws().region()))
                .credentialsProvider(DefaultCredentialsProvider.create());
        if (properties.aws().sqsEndpoint() != null && !properties.aws().sqsEndpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.aws().sqsEndpoint()));
        }
        return builder.build();
    }
}
