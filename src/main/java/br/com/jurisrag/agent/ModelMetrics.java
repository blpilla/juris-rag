package br.com.jurisrag.agent;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

@Component
public class ModelMetrics {
    private final MeterRegistry registry;
    public ModelMetrics(MeterRegistry registry) { this.registry = registry; }
    public void recordInputTokens(long count) {
        if (count > 0) registry.counter("jurisrag.model.tokens", "direction", "input").increment(count);
    }
    public void recordOutputTokens(long count) {
        if (count > 0) registry.counter("jurisrag.model.tokens", "direction", "output").increment(count);
    }
}
