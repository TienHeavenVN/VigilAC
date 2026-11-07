package ac.vigil.ai.runtime;

import java.util.UUID;

/**
 * Callback for finished inference jobs. Invoked on a worker thread — sink
 * implementations must be cheap, non-blocking and internally synchronized.
 * The default sink writes advisory monitor evidence only; it MUST NOT touch
 * violation levels.
 */
@FunctionalInterface
public interface AiInferenceSink {

    /** @param playerId player UUID; @param modelId which model scored; @param score anomaly score in [0,1]; @param latencyMillis wall time of the inference */
    void onScored(UUID playerId, String modelId, double score, long latencyMillis);

    /** Failed inference (model threw, timeout, invalid). Default no-op keeps counters authoritative. */
    default void onFailed(UUID playerId, String modelId, String reason) {
    }
}
