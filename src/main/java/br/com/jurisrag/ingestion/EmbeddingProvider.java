package br.com.jurisrag.ingestion;

import java.util.List;

public interface EmbeddingProvider {
    List<float[]> embed(List<String> texts);
    int dimensions();
    default String modelName() { return "unspecified"; }
}
