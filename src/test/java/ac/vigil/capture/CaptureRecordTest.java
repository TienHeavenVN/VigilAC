package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import ac.vigil.ai.telemetry.AiFeatureSchema;

class CaptureRecordTest {

    private static CaptureRecord sample() {
        double[] features = new double[AiFeatureSchema.FEATURE_COUNT];
        for (int i = 0; i < features.length; i++) {
            features[i] = i + 0.5D;
        }
        return new CaptureRecord(
                "a".repeat(32), "b".repeat(32), "1.21.8", "VANILLA", "MOUSE",
                20.0D, 31.0D, AiFeatureSchema.schemaHash(),
                "LEGITIMATE", "SERVER_TELEMETRY_CURATION", 0.9D,
                Instant.ofEpochMilli(1_762_500_000_000L), "unit-test", features, "consent-capture/1");
    }

    @Test
    void keySetAndOrderMatchTheStage3Contract() {
        String json = sample().toJsonLine();
        assertTrue(json.startsWith("{\"player_pseudonym\":"));
        // fixed order, features last (before optional provenance_note)
        List<String> order = List.of("\"player_pseudonym\":", "\"session_pseudonym\":", "\"mc_version\":",
                "\"client_category\":", "\"input_category\":", "\"tps_1m\":", "\"ping_ms\":",
                "\"feature_schema_hash\":", "\"label\":", "\"label_source\":", "\"label_confidence\":",
                "\"collected_utc\":", "\"scenario\":", "\"features\":[", "\"provenance_note\":");
        int last = -1;
        for (String key : order) {
            int at = json.indexOf(key);
            assertTrue(at > last, "key " + key + " out of order or missing in: " + json);
            last = at;
        }
    }

    @Test
    void featureHashIsTheJavaSchemaHash() {
        String json = sample().toJsonLine();
        assertTrue(json.contains("\"feature_schema_hash\":\"" + AiFeatureSchema.schemaHash() + "\""));
        // the golden lock: the same constant the Python pipeline asserts
        assertEquals("2f2b0a3eb4563552b5c995c285f7179ce34490c450c46b63e9dbc022916ee688", AiFeatureSchema.schemaHash());
    }

    @Test
    void collectedUtcMatchesPipelineFormat() {
        String json = sample().toJsonLine();
        var matcher = java.util.regex.Pattern.compile("\"collected_utc\":\"(\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}Z)\"").matcher(json);
        assertTrue(matcher.find(), json);
    }

    @Test
    void featuresArrayHas22FiniteNumbers() {
        String json = sample().toJsonLine();
        int start = json.indexOf("\"features\":[") + "\"features\":[".length();
        int end = json.indexOf(']', start);
        String[] parts = json.substring(start, end).split(",");
        assertEquals(22, parts.length);
        for (String part : parts) {
            assertFalse(part.isBlank());
            double v = Double.parseDouble(part);
            assertTrue(Double.isFinite(v));
        }
    }
}
