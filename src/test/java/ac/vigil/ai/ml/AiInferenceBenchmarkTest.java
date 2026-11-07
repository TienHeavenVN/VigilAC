package ac.vigil.ai.ml;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import ac.vigil.ai.ml.isolationforest.IsolationForestTrainer;
import ac.vigil.ai.ml.isolationforest.TrainedForest;
import ac.vigil.ai.telemetry.AiFeatureSchema;

/**
 * NOT a production benchmark — a regression tripwire with a deliberately generous bound.
 * Measures only the pure-Java isolation-forest scoring path in this JVM. Real deployment
 * latency on production hardware is NOT MEASURED anywhere in this repository.
 */
class AiInferenceBenchmarkTest {

    @Test
    void forestScoringStaysFarBelowTaskBudget() {
        double[][] samples = SyntheticForests.syntheticSamples(64, 0xBEEF);
        TrainedForest forest = IsolationForestTrainer.train(samples, 32, 32, 42L);
        double[] probe = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(probe, 0.5D);
        // warm-up
        for (int i = 0; i < 500; i++) {
            forest.anomalyScore(probe);
        }
        int iterations = 10_000;
        long start = System.nanoTime();
        double sink = 0.0D;
        for (int i = 0; i < iterations; i++) {
            sink += forest.anomalyScore(probe);
        }
        long elapsedMillis = (System.nanoTime() - start) / 1_000_000L;
        double avgNanos = (elapsedMillis * 1_000_000.0D) / iterations;
        System.out.printf(java.util.Locale.US,
                "AiInferenceBenchmarkTest: %d scores in %d ms (avg %.0f ns/score, sink=%.1f) — synthetic fixture, not production hardware%n",
                iterations, elapsedMillis, avgNanos, sink);
        assertTrue(sink > 0.0D);
        assertTrue(elapsedMillis < 10_000L,
                "10k scorings took " + elapsedMillis + " ms — something is pathologically wrong (bound is intentionally generous)");
    }
}
