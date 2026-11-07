package ac.vigil.ai.telemetry;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class FeatureValidatorTest {

    private static AiFeatureSnapshot snapshotWith(double fill) {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, fill);
        return new AiFeatureSnapshot(1_000L, v);
    }

    @Test
    void acceptsInRangeSnapshot() {
        AiFeatureSnapshot s = snapshotWith(0.5D);
        assertEquals(FeatureValidator.Result.OK, FeatureValidator.check(s));
        assertTrue(FeatureValidator.isUsable(s));
    }

    @Test
    void rejectsAllZeroSnapshotAsEmptySignal() {
        // every tracker reporting zero would pass range checks — but it carries no information
        assertEquals(FeatureValidator.Result.EMPTY_SIGNAL, FeatureValidator.check(snapshotWith(0.0D)));
    }

    @Test
    void rejectsWrongCount() {
        AiFeatureSnapshot s = new AiFeatureSnapshot(1_000L, new double[] {1.0D, 2.0D});
        assertEquals(FeatureValidator.Result.WRONG_COUNT, FeatureValidator.check(s));
    }

    @Test
    void rejectsNonFiniteValues() {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, 0.25D);
        v[7] = Double.NaN;
        assertEquals(FeatureValidator.Result.NON_FINITE, FeatureValidator.check(new AiFeatureSnapshot(1_000L, v)));
        v[7] = Double.POSITIVE_INFINITY;
        assertEquals(FeatureValidator.Result.NON_FINITE, FeatureValidator.check(new AiFeatureSnapshot(1_000L, v)));
    }

    @Test
    void rejectsOutOfRangeValues() {
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];
        java.util.Arrays.fill(v, 0.25D);
        v[0] = 900.0D; // cps_1s range is 0..50
        assertEquals(FeatureValidator.Result.OUT_OF_RANGE, FeatureValidator.check(new AiFeatureSnapshot(1_000L, v)));
    }
}
