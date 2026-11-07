package ac.vigil.ai.ml;

/** Raised when a model or its manifest fails to load or verify. Callers turn this into UNAVAILABLE + reason, never into a crash. */
public final class ModelException extends Exception {
    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
