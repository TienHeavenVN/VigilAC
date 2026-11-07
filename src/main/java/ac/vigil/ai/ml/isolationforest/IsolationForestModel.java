package ac.vigil.ai.ml.isolationforest;

import java.io.IOException;
import ac.vigil.ai.ml.AnomalyModel;
import ac.vigil.ai.ml.ModelException;
import ac.vigil.ai.ml.ModelManifest;
import ac.vigil.ai.telemetry.AiFeatureSchema;
import ac.vigil.ai.telemetry.AiFeatureSnapshot;

/**
 * Runtime wrapper around a {@link TrainedForest} for VigilAC inference.
 * Pure Java: no external natives, no reflection — always available.
 * Scoring is O(nTrees × depth) and bounded; safe for worker threads.
 */
public final class IsolationForestModel implements AnomalyModel {

    private final ModelManifest manifest;
    private final TrainedForest forest;

    public IsolationForestModel(ModelManifest manifest, byte[] artifactBytes) throws ModelException {
        if (manifest.algorithm() != ac.vigil.ai.ml.ModelAlgorithm.ISOLATION_FOREST) {
            throw new ModelException("manifest algorithm is " + manifest.algorithm() + ", expected ISOLATION_FOREST");
        }
        if (!manifest.matchesCurrentSchema()) {
            throw new ModelException("model was trained against a different feature schema (manifest hash "
                    + manifest.featureSchemaHash() + ", current schema " + AiFeatureSchema.schemaHash() + ")");
        }
        if (!manifest.verifyArtifact(artifactBytes)) {
            throw new ModelException("artifact SHA-256 does not match manifest for model '" + manifest.modelId() + "'");
        }
        try {
            this.forest = TrainedForest.read(artifactBytes);
        } catch (IOException e) {
            throw new ModelException("unreadable isolation forest artifact for model '" + manifest.modelId() + "': " + e.getMessage(), e);
        }
        if (forest.nFeatures() != AiFeatureSchema.FEATURE_COUNT) {
            throw new ModelException("forest declares " + forest.nFeatures() + " features; schema requires " + AiFeatureSchema.FEATURE_COUNT);
        }
        boolean manifestZ = manifest.normalization() == ModelManifest.Normalization.ZSCORE;
        if (manifestZ != forest.isZscore()) {
            throw new ModelException("manifest normalization (" + manifest.normalization() + ") disagrees with artifact ("
                    + (forest.isZscore() ? "zscore" : "none") + ") for model '" + manifest.modelId() + "'");
        }
        this.manifest = manifest;
    }

    @Override
    public ModelManifest manifest() {
        return manifest;
    }

    @Override
    public String modelId() {
        return manifest.modelId();
    }

    @Override
    public double anomalyScore(AiFeatureSnapshot snapshot) {
        if (snapshot == null || snapshot.size() != forest.nFeatures()) {
            throw new IllegalArgumentException("snapshot does not match model feature count");
        }
        return forest.anomalyScore(snapshot.values());
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public String status() {
        return "loaded";
    }
}
