package ac.vigil.ai.assistant;

/**
 * Optional Large Language Model back-end for the admin assistant. Implementations
 * send already-redacted text to an external API and MUST be explicitly enabled
 * in config (ai.admin-assistant.llm.enabled: true) — never by default.
 *
 * <p>VigilAC ships NO provider implementation. The interface exists so a server
 * owner can wire one; nothing phones home by itself.</p>
 */
public interface LlmProvider {

    /** Provider label for status/diagnostics, e.g. "none", "openai-compatible". */
    String providerName();

    /**
     * @param prompt redacted prompt text (caller applies TelemetryRedactor first)
     * @param timeoutMillis hard budget; providers must give up rather than block
     * @return completion text, or null when the provider is unavailable/disabled
     */
    String complete(String prompt, long timeoutMillis);
}
