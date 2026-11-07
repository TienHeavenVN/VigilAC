package ac.vigil.ai;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * AI module configuration, parsed from {@code ai.*} keys with strict defaults.
 * EVERY AI feature defaults to OFF / shadow-only. Nothing here can enable a
 * punishment path — there is no such path.
 *
 * <p>Keys and defaults (all under {@code ai:} in config.yml):</p>
 * <ul>
 *   <li>{@code enabled: false} — master switch; off means zero inference, zero model loading</li>
 *   <li>{@code shadow-mode: true} — enforced true; reserved for a future explicit owner decision, and
 *       relaxing it would require a code change, not a config flip</li>
 *   <li>{@code max-queue-size: 128}, {@code worker-threads: 1}, {@code inference-timeout-millis: 250}</li>
 *   <li>{@code inference-interval-seconds: 5} — scheduler period for the scan tick</li>
 *   <li>{@code models-directory: ai-models} — resolved against the plugin data folder</li>
 *   <li>{@code isolation-forest-threshold: 0.6} — shadow score threshold for correlate corroboration</li>
 *   <li>{@code require-deterministic-corroboration: true} — enforced true; see AiEvidenceCorrelator</li>
 *   <li>{@code min-combat-samples: 20}</li>
 *   <li>{@code admin-assistant.enabled: false}</li>
 *   <li>{@code admin-assistant.llm.enabled: false}, {@code admin-assistant.llm.endpoint: ""},
 *       {@code admin-assistant.llm.api-key-env: ""}, {@code admin-assistant.llm.redact-identifiers: true}</li>
 * </ul>
 */
public final class AiConfig {

    public static final String DEFAULT_MODELS_DIR = "ai-models";

    private final boolean enabled;
    private final boolean shadowMode;
    private final int maxQueueSize;
    private final int workerThreads;
    private final int inferenceTimeoutMillis;
    private final int inferenceIntervalSeconds;
    private final String modelsDirectory;
    private final double mlThreshold;
    private final boolean requireDeterministicCorroboration;
    private final int minCombatSamples;
    private final boolean adminAssistantEnabled;
    private final boolean llmEnabled;
    private final String llmEndpoint;
    private final String llmApiKeyEnv;
    private final boolean llmRedactIdentifiers;

    public AiConfig(ConfigSource source) {
        this.enabled = source.getBoolean("ai.enabled", false);
        this.shadowMode = source.getBoolean("ai.shadow-mode", true);
        this.maxQueueSize = clamp(source.getInt("ai.max-queue-size", 128), 1, 4096);
        this.workerThreads = clamp(source.getInt("ai.worker-threads", 1), 1, 4);
        this.inferenceTimeoutMillis = clamp(source.getInt("ai.inference-timeout-millis", 250), 1, 10_000);
        this.inferenceIntervalSeconds = clamp(source.getInt("ai.inference-interval-seconds", 5), 1, 3600);
        this.modelsDirectory = source.getString("ai.models-directory", DEFAULT_MODELS_DIR);
        this.mlThreshold = clampDouble(source.getDouble("ai.isolation-forest-threshold", 0.6D), 0.0D, 1.0D);
        this.requireDeterministicCorroboration = source.getBoolean("ai.require-deterministic-corroboration", true);
        this.minCombatSamples = clamp(source.getInt("ai.min-combat-samples", 20), 1, 10_000);
        this.adminAssistantEnabled = source.getBoolean("ai.admin-assistant.enabled", false);
        this.llmEnabled = source.getBoolean("ai.admin-assistant.llm.enabled", false);
        this.llmEndpoint = source.getString("ai.admin-assistant.llm.endpoint", "");
        this.llmApiKeyEnv = source.getString("ai.admin-assistant.llm.api-key-env", "");
        this.llmRedactIdentifiers = source.getBoolean("ai.admin-assistant.llm.redact-identifiers", true);
    }

    /** Non-fatal configuration problems, e.g. nothing is wrong but the LLM egress redaction is off. */
    public List<String> problems() {
        List<String> problems = new ArrayList<>();
        if (!shadowMode) {
            problems.add("ai.shadow-mode is not true — VigilAC has no non-shadow AI path; the flag is ignored");
        }
        if (!requireDeterministicCorroboration) {
            problems.add("ai.require-deterministic-corroboration is not true — ML scores are excluded from tiers regardless (hard rule)");
        }
        if (llmEnabled && !llmRedactIdentifiers) {
            problems.add("LLM is enabled with redact-identifiers=false — player identifiers may be sent to an external API");
        }
        if (llmEnabled && llmEndpoint.isEmpty()) {
            problems.add("LLM is enabled but no endpoint is configured — the offline template fallback will be used");
        }
        if (enabled && modelsDirectory.trim().isEmpty()) {
            problems.add("ai.enabled is true but models-directory is blank — no models will load");
        }
        return Collections.unmodifiableList(problems);
    }

    public boolean enabled() { return enabled; }
    public boolean shadowMode() { return true; } // enforced: shadow-only, always
    public int maxQueueSize() { return maxQueueSize; }
    public int workerThreads() { return workerThreads; }
    public int inferenceTimeoutMillis() { return inferenceTimeoutMillis; }
    public int inferenceIntervalSeconds() { return inferenceIntervalSeconds; }
    public String modelsDirectory() { return modelsDirectory; }
    public double mlThreshold() { return mlThreshold; }
    public boolean requireDeterministicCorroboration() { return true; } // enforced
    public int minCombatSamples() { return minCombatSamples; }
    public boolean adminAssistantEnabled() { return adminAssistantEnabled; }
    public boolean llmEnabled() { return llmEnabled; }
    public String llmEndpoint() { return llmEndpoint; }
    public String llmApiKeyEnv() { return llmApiKeyEnv; }
    public boolean llmRedactIdentifiers() { return llmRedactIdentifiers; }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private static double clampDouble(double value, double lo, double hi) {
        if (Double.isNaN(value)) {
            return (lo + hi) / 2.0D;
        }
        return Math.max(lo, Math.min(hi, value));
    }
}
