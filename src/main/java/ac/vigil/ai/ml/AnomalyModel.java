package ac.vigil.ai.ml;

import ac.vigil.ai.telemetry.AiFeatureSnapshot;

/**
 * One loaded, checksum-verified anomaly model. Implementations must be pure
 * and thread-safe: scoring happens on worker threads, never on Netty/main.
 */
public interface AnomalyModel {

    ModelManifest manifest();

    /** Model identifier from the manifest (stable, human-meaningful). */
    String modelId();

    /**
     * Anomaly score in [0, 1]; higher means more anomalous. Implementations
     * apply the manifest's normalization themselves, so callers always pass
     * the raw (validated) snapshot.
     *
     * @throws IllegalArgumentException when the snapshot does not match the schema
     */
    double anomalyScore(AiFeatureSnapshot snapshot);

    /** True when the model is loaded and ready to score. */
    boolean available();

    /** "loaded" when ready, otherwise a short honest reason for being unavailable. */
    String status();
}
