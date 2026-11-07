package ac.vigil.ai.ml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Random;
import java.util.StringJoiner;
import ac.vigil.ai.ml.isolationforest.IsolationForestTrainer;
import ac.vigil.ai.ml.isolationforest.TrainedForest;
import ac.vigil.ai.telemetry.AiFeatureSchema;

/**
 * Builds tiny SYNTHETIC forests for model-pipeline tests only. These fixtures prove
 * packaging/integrity/rollback mechanics — they are NOT gameplay data and must never
 * be used to claim detection quality.
 */
public final class SyntheticForests {

    private SyntheticForests() {
    }

    public static double[][] syntheticSamples(int rows, long seed) {
        Random random = new Random(seed);
        double[][] samples = new double[rows][AiFeatureSchema.FEATURE_COUNT];
        for (int r = 0; r < rows; r++) {
            for (int f = 0; f < AiFeatureSchema.FEATURE_COUNT; f++) {
                samples[r][f] = random.nextDouble() * 2.0D;
            }
        }
        return samples;
    }

    /** Writes <artifactName> and <manifestName> for a freshly trained forest; returns the artifact bytes. */
    public static byte[] writeForest(Path dir, String modelId, String artifactName,
                                     double[][] samples, long seed) throws IOException {
        TrainedForest forest = IsolationForestTrainer.train(samples, 8, 32, seed);
        byte[] artifact = forest.write();
        Path artifactPath = dir.resolve(artifactName);
        Files.write(artifactPath, artifact);
        StringJoiner means = new StringJoiner(",");
        StringJoiner stddevs = new StringJoiner(",");
        for (double m : forest.means()) {
            means.add(Double.toString(m));
        }
        for (double sd : forest.stddevs()) {
            stddevs.add(Double.toString(sd));
        }
        String manifest = String.join("\n",
                ModelManifest.MAGIC,
                "algorithm=ISOLATION_FOREST",
                "formatVersion=1",
                "modelId=" + modelId,
                "artifact=" + artifactName,
                "sha256=" + ModelManifest.sha256Hex(artifact),
                "featureSchemaHash=" + AiFeatureSchema.schemaHash(),
                "featureCount=" + AiFeatureSchema.FEATURE_COUNT,
                "normalization=zscore",
                "means=" + means,
                "stddevs=" + stddevs,
                "createdUtc=2026-11-07T00:00:00Z",
                "notes=synthetic unit-test fixture; NOT a trained anti-cheat model");
        Files.writeString(dir.resolve(modelId + ".manifest"), manifest, StandardCharsets.UTF_8);
        return artifact;
    }

}
