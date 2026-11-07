package ac.vigil.ai.telemetry;

import java.util.Arrays;

/**
 * One immutable, validated vector of AI features for a single player at a
 * point in time. Instances are produced by {@code FeatureExtractor}, checked
 * by {@code FeatureValidator}, and consumed by ML models. No names, UUIDs or
 * any identity are embedded — callers track identity alongside, never inside.
 */
public final class AiFeatureSnapshot {

    private final long timestampMillis;
    private final double[] values; // ordered per AiFeatureSchema

    public AiFeatureSnapshot(long timestampMillis, double[] values) {
        this.timestampMillis = timestampMillis;
        this.values = values.clone();
    }

    public long timestampMillis() {
        return timestampMillis;
    }

    /** Defensive copy of the raw feature values, in schema order. */
    public double[] values() {
        return values.clone();
    }

    /** Raw value at {@code index} (schema order). */
    public double value(int index) {
        return values[index];
    }

    /** Number of features; always {@link AiFeatureSchema#FEATURE_COUNT} for valid snapshots. */
    public int size() {
        return values.length;
    }

    /** Z-score copy using the supplied means/stddevs (index-aligned). Constant features (stddev 0) pass through as 0. */
    public AiFeatureSnapshot normalized(double[] means, double[] stddevs) {
        if (means.length != values.length || stddevs.length != values.length) {
            throw new IllegalArgumentException("normalization arrays must match feature count");
        }
        double[] out = new double[values.length];
        for (int i = 0; i < values.length; i++) {
            double sd = stddevs[i];
            out[i] = sd == 0.0D ? 0.0D : (values[i] - means[i]) / sd;
        }
        return new AiFeatureSnapshot(timestampMillis, out);
    }

    @Override
    public String toString() {
        return "AiFeatureSnapshot{ts=" + timestampMillis + ", features=" + Arrays.toString(values) + "}";
    }
}
