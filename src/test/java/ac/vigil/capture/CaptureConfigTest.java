package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.ConfigSource;

class CaptureConfigTest {

    private static final class MapSource implements ConfigSource {
        final Map<String, Object> values = new HashMap<>();

        MapSource with(String key, Object value) { values.put(key, value); return this; }

        @Override public String getString(String path, String def) {
            Object v = values.get(path);
            return v instanceof String s ? s : def;
        }
        @Override public int getInt(String path, int def) {
            Object v = values.get(path);
            return v instanceof Number n ? n.intValue() : def;
        }
        @Override public double getDouble(String path, double def) {
            Object v = values.get(path);
            return v instanceof Number n ? n.doubleValue() : def;
        }
        @Override public boolean getBoolean(String path, boolean def) {
            Object v = values.get(path);
            return v instanceof Boolean b ? b : def;
        }
        @Override public List<String> getStringList(String path, List<String> def) {
            return def;
        }
    }

    private static MapSource validLab() {
        return new MapSource()
                .with("capture.enabled", true)
                .with("capture.scenario", "lab-autoclicker-20cps")
                .with("capture.label", "CHEAT_VERIFIED")
                .with("capture.label-source", "CONTROLLED_LAB")
                .with("capture.label-confidence", 0.95)
                .with("capture.client-category", "LAB_CHEAT_AUTOCLICK")
                .with("capture.input-category", "MOUSE")
                .with("capture.pseudonym-key", java.util.Base64.getEncoder().encodeToString(new byte[32]));
    }

    @Test
    void disabledByDefaultNeedsNothing() {
        CaptureConfig config = new CaptureConfig(new MapSource());
        assertFalse(config.enabled());
        assertTrue(config.problems().isEmpty());
        assertFalse(config.ready());
    }

    @Test
    void enabledButUndeclaredIsRefused() {
        CaptureConfig config = new CaptureConfig(new MapSource().with("capture.enabled", true));
        List<String> problems = config.problems();
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("scenario")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("label")));
        assertTrue(problems.stream().anyMatch(p -> p.contains("pseudonym-key")));
        assertFalse(config.ready());
    }

    @Test
    void fullDeclarationIsReady() {
        CaptureConfig config = new CaptureConfig(validLab());
        assertTrue(config.problems().isEmpty(), () -> String.join("\n", config.problems()));
        assertTrue(config.ready());
    }

    @Test
    void cheatVerifiedNeedsLabSourceAndHighConfidence() {
        assertTrue(new CaptureConfig(validLab().with("capture.label-source", "HEURISTIC_FLAG"))
                .problems().stream().anyMatch(p -> p.contains("CONTROLLED_LAB or MODERATOR_REVIEW")));
        assertTrue(new CaptureConfig(validLab().with("capture.label-confidence", 0.7))
                .problems().stream().anyMatch(p -> p.contains(">= 0.8")));
        assertTrue(new CaptureConfig(validLab().with("capture.label-source", "MODERATOR_REVIEW").with("capture.label-confidence", 0.8))
                .problems().isEmpty());
    }

    @Test
    void legitimateScenarioWithCurationSourceIsReady() {
        MapSource legit = validLab()
                .with("capture.scenario", "ranked-legit-farm")
                .with("capture.label", "LEGITIMATE")
                .with("capture.label-source", "SERVER_TELEMETRY_CURATION")
                .with("capture.label-confidence", 0.9)
                .with("capture.client-category", "VANILLA");
        assertTrue(new CaptureConfig(legit).ready());
    }

    @Test
    void suspiciousWithHeuristicFlagIsAllowed() {
        MapSource suspicious = validLab()
                .with("capture.label", "SUSPICIOUS")
                .with("capture.label-source", "HEURISTIC_FLAG")
                .with("capture.label-confidence", 0.5);
        assertTrue(new CaptureConfig(suspicious).ready());
    }

    @Test
    void badInputCategoryAndKeyAreRejected() {
        assertTrue(new CaptureConfig(validLab().with("capture.input-category", "GAMEPAD"))
                .problems().stream().anyMatch(p -> p.contains("input-category")));
        assertTrue(new CaptureConfig(validLab().with("capture.pseudonym-key", "!!!not-base64!!!"))
                .problems().stream().anyMatch(p -> p.contains("pseudonym-key")));
        assertTrue(new CaptureConfig(validLab().with("capture.pseudonym-key",
                        java.util.Base64.getEncoder().encodeToString(new byte[8])))
                .problems().stream().anyMatch(p -> p.contains("pseudonym-key")));
    }
}
