package br.com.jurisrag.ingestion;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Embeddings previsíveis de hashing, usados apenas em testes e na demo offline. */
public class DeterministicEmbeddingProvider implements EmbeddingProvider {
    private static final Pattern WORD = Pattern.compile("[\\p{L}0-9]+", Pattern.UNICODE_CHARACTER_CLASS);
    private final int dimensions;

    public DeterministicEmbeddingProvider(int dimensions) {
        if (dimensions <= 0) throw new IllegalArgumentException("A dimensão precisa ser positiva.");
        this.dimensions = dimensions;
    }

    @Override
    public List<float[]> embed(List<String> texts) {
        List<float[]> result = new ArrayList<>(texts.size());
        for (String text : texts) result.add(vector(text));
        return result;
    }

    @Override
    public int dimensions() { return dimensions; }

    @Override
    public String modelName() { return "deterministic-hash-v1"; }

    private float[] vector(String text) {
        float[] vector = new float[dimensions];
        String normalized = Normalizer.normalize(text == null ? "" : text, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
        Matcher matcher = WORD.matcher(normalized);
        while (matcher.find()) {
            String token = matcher.group();
            int hash = token.hashCode();
            int position = Math.floorMod(hash, dimensions);
            vector[position] += (hash & 0x40000000) == 0 ? 1.0f : -1.0f;
        }
        double norm = 0.0;
        for (float value : vector) norm += (double) value * value;
        if (norm > 0) {
            float denominator = (float) Math.sqrt(norm);
            for (int i = 0; i < vector.length; i++) vector[i] /= denominator;
        }
        return vector;
    }
}
