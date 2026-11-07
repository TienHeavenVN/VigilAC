package ac.vigil.ai.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class AiFeatureSchemaTest {

    @Test
    void schemaHasExpectedSizeAndUniqueNames() {
        assertEquals(22, AiFeatureSchema.FEATURE_COUNT);
        assertEquals(22, AiFeatureSchema.NAMES.length);
        assertEquals(AiFeatureSchema.FEATURE_COUNT, AiFeatureSchema.size());
        for (String name : AiFeatureSchema.NAMES) {
            assertFalse(name.isEmpty(), "feature names must be non-empty");
        }
        long distinct = java.util.Arrays.stream(AiFeatureSchema.NAMES).distinct().count();
        assertEquals(AiFeatureSchema.NAMES.length, distinct, "feature names must be unique");
    }

    @Test
    void schemaHashIsStable64Hex() {
        String hash = AiFeatureSchema.schemaHash();
        assertEquals(64, hash.length());
        assertTrue(hash.matches("[0-9a-f]{64}"));
        assertEquals(hash, AiFeatureSchema.schemaHash(), "schema hash must be deterministic");
    }

    @Test
    void rangesAreWellFormed() {
        for (int i = 0; i < AiFeatureSchema.FEATURE_COUNT; i++) {
            double[] range = AiFeatureSchema.rangeAt(i);
            assertEquals(2, range.length);
            assertTrue(range[0] <= range[1], "range at " + i + " must be ordered");
            assertTrue(range[0] >= 0.0D, "all feature ranges start at >= 0");
        }
    }

    @Test
    void nameAtMatchesNames() {
        for (int i = 0; i < AiFeatureSchema.FEATURE_COUNT; i++) {
            assertEquals(AiFeatureSchema.NAMES[i], AiFeatureSchema.nameAt(i));
        }
    }
}
