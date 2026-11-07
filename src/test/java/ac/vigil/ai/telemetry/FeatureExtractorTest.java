package ac.vigil.ai.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import ac.vigil.data.PlayerData;

class FeatureExtractorTest {

    private static PlayerData freshPlayer() {
        return new PlayerData(UUID.randomUUID(), () -> 10_000L);
    }

    private static AiContext context(long now) {
        return new AiContext(now, 42.0D, 19.5D, true, false, false, 0);
    }

    @Test
    void freshPlayerProducesInRangeSnapshot() {
        PlayerData data = freshPlayer();
        AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context(10_000L));
        assertEquals(AiFeatureSchema.FEATURE_COUNT, snapshot.size());
        for (int i = 0; i < AiFeatureSchema.FEATURE_COUNT; i++) {
            double[] range = AiFeatureSchema.rangeAt(i);
            assertTrue(snapshot.value(i) >= range[0], "feature " + i + " below range");
            assertTrue(snapshot.value(i) <= range[1], "feature " + i + " above range");
        }
        // all-zero would be rejected by the validator as an empty signal; a fresh
        // player still emits context flags (ping=42, tps=19.5, onGround=1), so OK.
        assertEquals(FeatureValidator.Result.OK, FeatureValidator.check(snapshot));
    }

    @Test
    void pingIsClippedToSchemaRange() {
        PlayerData data = freshPlayer();
        AiContext context = new AiContext(10_000L, 99_999.0D, 20.0D, false, false, false, 0);
        AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context);
        assertEquals(AiFeatureSchema.rangeAt(19)[1], snapshot.value(19), "ping must clip to schema max");
        assertEquals(0.0D, snapshot.value(21), "on_ground flag reflects context");
    }

    @Test
    void nanContextInputsMapToRangeFloorNotGuesses() {
        PlayerData data = freshPlayer();
        AiContext context = new AiContext(10_000L, Double.NaN, Double.POSITIVE_INFINITY, true, false, false, 0);
        AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context);
        assertEquals(AiFeatureSchema.rangeAt(19)[0], snapshot.value(19));
        assertEquals(AiFeatureSchema.rangeAt(20)[0], snapshot.value(20));
    }

    @Test
    void breakIntervalWithoutHistoryIsZeroNotNegative() {
        PlayerData data = freshPlayer();
        AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context(10_000L));
        assertEquals(0.0D, snapshot.value(17), "no break history maps to the schema floor, never a sentinel");
    }

    @Test
    void missingMeasurementsAreZeroNeverInterpolated() {
        PlayerData data = freshPlayer();
        AiFeatureSnapshot snapshot = FeatureExtractor.extract(data, context(10_000L));
        // untouched reach trackers: EMA 0 with 0 samples — both legitimately zero
        assertEquals(0.0D, snapshot.value(11));
        assertEquals(0.0D, snapshot.value(12));
    }
}
