package ac.vigil.ai.assistant;

/**
 * Default provider: refuses to call out. The assistant answers from offline
 * templates instead. This is the ONLY implementation shipped with VigilAC.
 */
public final class NoopLlmProvider implements LlmProvider {

    @Override
    public String providerName() {
        return "none";
    }

    @Override
    public String complete(String prompt, long timeoutMillis) {
        return null; // signal to fall back to offline templates
    }
}
