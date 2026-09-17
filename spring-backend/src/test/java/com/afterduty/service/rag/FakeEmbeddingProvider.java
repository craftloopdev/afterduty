package com.afterduty.service.rag;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic embedding test seam (spec §H.1). Seeds a 768-float vector from the
 * hash of the text, normalized — so the same text always yields the same vector and
 * different texts differ. Records calls; can be toggled unavailable or throwing.
 */
public class FakeEmbeddingProvider implements EmbeddingProvider {

    public final List<String> embeddedTexts = new ArrayList<>();
    public final List<TaskType> tasks = new ArrayList<>();
    public int callCount = 0;

    private boolean available = true;
    private boolean throwing = false;
    private final int dimensions;

    public FakeEmbeddingProvider() { this(768); }

    public FakeEmbeddingProvider(int dimensions) { this.dimensions = dimensions; }

    public FakeEmbeddingProvider available(boolean v) { this.available = v; return this; }

    public FakeEmbeddingProvider throwing(boolean v) { this.throwing = v; return this; }

    @Override
    public boolean isAvailable() { return available; }

    @Override
    public float[] embed(String text, TaskType task) {
        return embedBatch(List.of(text), task).get(0);
    }

    @Override
    public List<float[]> embedBatch(List<String> texts, TaskType task) {
        callCount++;
        if (throwing) {
            throw new EmbeddingUnavailableException("fake embedding provider forced failure");
        }
        List<float[]> out = new ArrayList<>(texts.size());
        for (String text : texts) {
            embeddedTexts.add(text);
            tasks.add(task);
            out.add(deterministicVector(text));
        }
        return out;
    }

    /** Seeded, normalized 768-float vector — stable per text. */
    public float[] deterministicVector(String text) {
        long seed = text == null ? 0 : text.hashCode();
        java.util.Random rng = new java.util.Random(seed);
        float[] vec = new float[dimensions];
        double sumSq = 0;
        for (int i = 0; i < dimensions; i++) {
            vec[i] = (float) rng.nextGaussian();
            sumSq += (double) vec[i] * vec[i];
        }
        double norm = Math.sqrt(sumSq);
        if (norm > 0) {
            for (int i = 0; i < dimensions; i++) vec[i] /= (float) norm;
        }
        return vec;
    }
}
