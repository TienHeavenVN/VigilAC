package ac.vigil.ai.ml.isolationforest;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Random;
import java.util.StringJoiner;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.ml.ModelException;
import ac.vigil.ai.ml.ModelManifest;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;

/**
 * Synthetic-fixture tests only: these prove the trainer/serializer/loader/wrapper
 * are self-consistent and deterministic. They say NOTHING about detection quality —
 * no real dataset was used anywhere in this repository.
 */
class IsolationForestPipelineTest {

    private static double[][] syntheticSamples(int rows) {
        Random random = new Random(0xC0FFEE);
        double[][] samples = new double[rows][AiFeatureSchema.FEATURE_COUNT];
        for (int r = 0; r < rows; r++) {
            for (int f = 0; f < AiFeatureSchema.FEATURE_COUNT; f++) {
                samples[r][f] = random.nextDouble() * 2.0D;
            }
        }
        return samples;
    }

    @Test
    void trainerRejectsTooFewSamples() {
        assertThrows(IllegalArgumentException.class,
                () -> IsolationForestTrainer.train(syntheticSamples(IsolationForestTrainer.MIN_TRAINING_SAMPLES - 1), 10, 32, 42L));
    }

    @Test
    void trainerIsDeterministicForSameSeed() {
        double[][] samples = syntheticSamples(64);
        TrainedForest a = IsolationForestTrainer.train(samples, 10, 32, 42L);
        TrainedForest b = IsolationForestTrainer.train(samples, 10, 32, 42L);
        assertArrayEquals(a.write(), b.write(), "same inputs + seed must yield bit-identical artifacts");
    }

    @Test
    void scoresAreInUnitInterval() {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 16, 32, 42L);
        double[] probe = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(probe, 0.5D);
        double score = forest.anomalyScore(probe);
        assertTrue(score >= 0.0D && score <= 1.0D, "score must lie in [0,1], got " + score);
    }

    @Test
    void writeReadRoundTripPreservesScoresExactly() throws Exception {
        double[][] samples = syntheticSamples(96);
        TrainedForest forest = IsolationForestTrainer.train(samples, 12, 32, 7L);
        byte[] bytes = forest.write();
        TrainedForest reloaded = TrainedForest.read(bytes);
        double[] probe = new double[AiFeatureSchema.FEATURE_COUNT];
        Random random = new Random(99L);
        for (int trial = 0; trial < 8; trial++) {
            for (int f = 0; f < probe.length; f++) {
                probe[f] = random.nextDouble() * 2.0D;
            }
            assertEquals(forest.anomalyScore(probe), reloaded.anomalyScore(probe), 0.0D,
                    "round-trip must not perturb scores at all");
        }
    }

    @Test
    void truncatedArtifactIsRejected() {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(32), 4, 16, 1L);
        byte[] bytes = forest.write();
        byte[] truncated = java.util.Arrays.copyOf(bytes, bytes.length - 17);
        assertThrows(java.io.IOException.class, () -> TrainedForest.read(truncated));
    }

    private static String manifestFor(TrainedForest forest, byte[] artifactBytes,
                                      ModelManifest.Normalization normalization, String algorithm) {
        StringBuilder sb = new StringBuilder();
        sb.append(ModelManifest.MAGIC).append('\n');
        sb.append("algorithm=").append(algorithm).append('\n');
        sb.append("formatVersion=1\n");
        sb.append("modelId=unit-test-model\n");
        sb.append("artifact=unit-test-model.vif\n");
        sb.append("sha256=").append(ModelManifest.sha256Hex(artifactBytes)).append('\n');
        sb.append("featureSchemaHash=").append(AiFeatureSchema.schemaHash()).append('\n');
        sb.append("featureCount=").append(AiFeatureSchema.FEATURE_COUNT).append('\n');
        if (normalization == ModelManifest.Normalization.ZSCORE) {
            StringJoiner means = new StringJoiner(",");
            StringJoiner stddevs = new StringJoiner(",");
            for (double m : forest.means()) {
                means.add(Double.toString(m));
            }
            for (double sd : forest.stddevs()) {
                stddevs.add(Double.toString(sd));
            }
            sb.append("normalization=zscore\nmeans=").append(means).append('\n').append("stddevs=").append(stddevs).append('\n');
        } else {
            sb.append("normalization=none\n");
        }
        sb.append("createdUtc=2026-11-07T00:00:00Z\n");
        sb.append("notes=unit-test artifact; synthetic fixture; NOT a trained anti-cheat model");
        return sb.toString();
    }

    @Test
    void modelLoadsAndScoresThroughManifest() throws Exception {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 8, 32, 42L);
        byte[] artifact = forest.write();
        ModelManifest manifest = ModelManifest.parse(
                manifestFor(forest, artifact, ModelManifest.Normalization.ZSCORE, "ISOLATION_FOREST")
                        .getBytes(StandardCharsets.UTF_8));
        IsolationForestModel model = new IsolationForestModel(manifest, artifact);
        assertTrue(model.available());
        assertEquals("loaded", model.status());
        assertEquals("unit-test-model", model.modelId());
        AiFeatureSnapshot snapshot = new AiFeatureSnapshot(1_000L, fill(0.5D));
        double score = model.anomalyScore(snapshot);
        assertTrue(score >= 0.0D && score <= 1.0D);
    }

    @Test
    void corruptedArtifactFailsChecksum() throws Exception {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 8, 32, 42L);
        byte[] artifact = forest.write();
        ModelManifest manifest = ModelManifest.parse(
                manifestFor(forest, artifact, ModelManifest.Normalization.ZSCORE, "ISOLATION_FOREST")
                        .getBytes(StandardCharsets.UTF_8));
        byte[] tampered = artifact.clone();
        tampered[tampered.length / 2] ^= 0x01;
        assertThrows(ModelException.class, () -> new IsolationForestModel(manifest, tampered));
    }

    @Test
    void normalizationMismatchBetweenManifestAndArtifactIsRejected() throws Exception {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 8, 32, 42L);
        byte[] artifact = forest.write();
        // Forest is always zscore here (trainer always emits stats) — claim none in the manifest
        ModelManifest manifest = ModelManifest.parse(
                manifestFor(forest, artifact, ModelManifest.Normalization.NONE, "ISOLATION_FOREST")
                        .getBytes(StandardCharsets.UTF_8));
        ModelException e = assertThrows(ModelException.class, () -> new IsolationForestModel(manifest, artifact));
        assertTrue(e.getMessage().toLowerCase(Locale.ROOT).contains("normaliz"));
    }

    @Test
    void foreignSchemaHashIsRejected() throws Exception {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 8, 32, 42L);
        byte[] artifact = forest.write();
        String foreign = "c".repeat(64);
        String text = manifestFor(forest, artifact, ModelManifest.Normalization.ZSCORE, "ISOLATION_FOREST")
                .replace(AiFeatureSchema.schemaHash(), foreign);
        ModelManifest manifest = ModelManifest.parse(text.getBytes(StandardCharsets.UTF_8));
        assertThrows(ModelException.class, () -> new IsolationForestModel(manifest, artifact));
    }

    @Test
    void onnxAlgorithmInForestLoaderIsRejected() throws Exception {
        TrainedForest forest = IsolationForestTrainer.train(syntheticSamples(64), 8, 32, 42L);
        byte[] artifact = forest.write();
        ModelManifest manifest = ModelManifest.parse(
                manifestFor(forest, artifact, ModelManifest.Normalization.ZSCORE, "ONNX")
                        .getBytes(StandardCharsets.UTF_8));
        assertThrows(ModelException.class, () -> new IsolationForestModel(manifest, artifact));
    }

    private static double[] fill(double value) {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, value);
        return v;
    }
}
