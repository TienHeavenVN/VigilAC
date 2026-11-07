package ac.vigil.ai.telemetry;

/**
 * Hard validation of feature snapshots before they are scored. Any failure
 * means the sample is dropped (counted as {@code invalidSnapshots}), never
 * "clipped into plausibility". Validation is the last line of defence between
 * engine telemetry and ML inference — do not weaken it.
 *
 * <p>Rejects, with a human-readable reason:</p>
 * <ul>
 *   <li>wrong feature count (model/schema drift),</li>
 *   <li>NaN or infinite values,</li>
 *   <li>values outside the documented per-feature ranges,</li>
 *   <li>all-zero vectors from a player with literally zero telemetry
 *       (an extraction fault, not "perfectly average behaviour").</li>
 * </ul>
 */
public final class FeatureValidator {

    public enum Result { OK, WRONG_COUNT, NON_FINITE, OUT_OF_RANGE, EMPTY_SIGNAL }

    private FeatureValidator() {
    }

    public static Result check(AiFeatureSnapshot snapshot) {
        if (snapshot == null) {
            return Result.WRONG_COUNT;
        }
        double[] v = snapshot.values();
        if (v.length != AiFeatureSchema.FEATURE_COUNT) {
            return Result.WRONG_COUNT;
        }
        boolean allZero = true;
        for (int i = 0; i < v.length; i++) {
            double x = v[i];
            if (Double.isNaN(x) || Double.isInfinite(x)) {
                return Result.NON_FINITE;
            }
            double[] range = AiFeatureSchema.rangeAt(i);
            if (x < range[0] || x > range[1]) {
                return Result.OUT_OF_RANGE;
            }
            if (x != 0.0D) {
                allZero = false;
            }
        }
        return allZero ? Result.EMPTY_SIGNAL : Result.OK;
    }

    public static boolean isUsable(AiFeatureSnapshot snapshot) {
        return check(snapshot) == Result.OK;
    }
}
