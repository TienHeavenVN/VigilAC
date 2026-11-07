package ac.vigil.ai.ml;

/**
 * Algorithms VigilAC can load at runtime. XGBoost is a *training-time*
 * supervised classifier exported to ONNX — there is no Java XGBoost runtime
 * path; see docs/AI_ARCHITECTURE.md. Keeping this enum tiny is intentional.
 */
public enum ModelAlgorithm {
    /** Unsupervised anomaly detection, served by the built-in pure-Java implementation. */
    ISOLATION_FOREST,
    /** Served by ONNX Runtime (optional, reflection-guarded, see ml.onnx). */
    ONNX,
    /** Registered for honesty: manifest may exist, but there is no runtime path (train-time only). */
    XGBOOST
}
