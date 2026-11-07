package ac.vigil.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class AiConfigTest {

    /** In-memory ConfigSource — no Bukkit anywhere near the AI config. */
    private static final class MapSource implements ConfigSource {
        private final Map<String, Object> values = new HashMap<>();

        MapSource with(String key, Object value) {
            values.put(key, value);
            return this;
        }

        @Override
        public String getString(String path, String defaultValue) {
            Object v = values.get(path);
            return v instanceof String s ? s : defaultValue;
        }

        @Override
        public int getInt(String path, int defaultValue) {
            Object v = values.get(path);
            return v instanceof Number n ? n.intValue() : defaultValue;
        }

        @Override
        public double getDouble(String path, double defaultValue) {
            Object v = values.get(path);
            return v instanceof Number n ? n.doubleValue() : defaultValue;
        }

        @Override
        public boolean getBoolean(String path, boolean defaultValue) {
            Object v = values.get(path);
            return v instanceof Boolean b ? b : defaultValue;
        }

        @Override
        public List<String> getStringList(String path, List<String> defaultValue) {
            Object v = values.get(path);
            @SuppressWarnings("unchecked")
            List<String> list = v instanceof List<?> l ? (List<String>) l : defaultValue;
            return list;
        }
    }

    @Test
    void defaultsAreAllOffAndShadow() {
        AiConfig config = new AiConfig(new MapSource());
        assertFalse(config.enabled(), "AI must default to disabled");
        assertTrue(config.shadowMode(), "shadow-mode is enforced true");
        assertEquals(128, config.maxQueueSize());
        assertEquals(1, config.workerThreads());
        assertEquals(250, config.inferenceTimeoutMillis());
        assertEquals(5, config.inferenceIntervalSeconds());
        assertEquals(AiConfig.DEFAULT_MODELS_DIR, config.modelsDirectory());
        assertEquals(0.6D, config.mlThreshold(), 1e-9);
        assertTrue(config.requireDeterministicCorroboration(), "corroboration is enforced true");
        assertEquals(20, config.minCombatSamples());
        assertFalse(config.adminAssistantEnabled(), "admin assistant must default to disabled");
        assertFalse(config.llmEnabled(), "LLM must default to disabled");
        assertTrue(config.llmRedactIdentifiers(), "LLM redaction must default to true");
        assertTrue(config.problems().isEmpty(), "default config must have no problems: " + config.problems());
    }

    @Test
    void overridesAreHonoredAndClamped() {
        AiConfig config = new AiConfig(new MapSource()
                .with("ai.enabled", true)
                .with("ai.worker-threads", 99)
                .with("ai.max-queue-size", 0)
                .with("ai.isolation-forest-threshold", 1.7D));
        assertTrue(config.enabled());
        assertEquals(4, config.workerThreads(), "worker threads clamp to 4");
        assertEquals(1, config.maxQueueSize(), "queue clamps to >= 1");
        assertEquals(1.0D, config.mlThreshold(), 1e-9, "threshold clamps to [0,1]");
    }

    @Test
    void swallowingShadowModeFlagIsRejectedInProblems() {
        AiConfig config = new AiConfig(new MapSource().with("ai.shadow-mode", false));
        assertTrue(config.shadowMode(), "shadow-mode cannot be turned off by config");
        assertFalse(config.problems().isEmpty(), "attempt must be reported as a config problem");
    }

    @Test
    void corroborationFlagCannotBeWeakened() {
        AiConfig config = new AiConfig(new MapSource().with("ai.require-deterministic-corroboration", false));
        assertTrue(config.requireDeterministicCorroboration(), "ML-only corroboration cannot be enabled");
        assertFalse(config.problems().isEmpty());
    }

    @Test
    void llmProblemsAreReportedNotFatal() {
        AiConfig missingEndpoint = new AiConfig(new MapSource().with("ai.admin-assistant.llm.enabled", true));
        assertTrue(missingEndpoint.problems().stream().anyMatch(p -> p.contains("endpoint")));

        AiConfig noRedaction = new AiConfig(new MapSource()
                .with("ai.admin-assistant.llm.enabled", true)
                .with("ai.admin-assistant.llm.endpoint", "https://example.invalid/v1/chat")
                .with("ai.admin-assistant.llm.redact-identifiers", false));
        assertTrue(noRedaction.problems().stream().anyMatch(p -> p.contains("redact")));
    }
}
