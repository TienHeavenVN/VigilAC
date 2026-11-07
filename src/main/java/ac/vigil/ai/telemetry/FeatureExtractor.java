package ac.vigil.ai.telemetry;

import ac.vigil.data.PlayerData;

/**
 * Builds an {@link AiFeatureSnapshot} from existing VigilAC telemetry. Pure
 * Java (no Bukkit), O(bounded windows) per call — same complexity budget as
 * the trackers themselves. Never allocates inside packet paths; called on the
 * scheduler tick, not per packet.
 *
 * <p>UNKNOWN values are mapped to the schema's floor (0) instead of guessed:
 * e.g. a block break whose interval is undefined yields 0 ms (floor), and the
 * validator/model must tolerate that mass point. Nothing here fabricates a
 * measurement; missing data stays missing (zero), never interpolated.</p>
 */
public final class FeatureExtractor {

    private FeatureExtractor() {
    }

    public static AiFeatureSnapshot extract(PlayerData data, AiContext context) {
        long now = context.nowMillis();
        double[] v = new double[AiFeatureSchema.FEATURE_COUNT];

        // ---- combat cadence (CombatTracker / SwingTracker)
        v[0] = clip(data.combatTracker().cps(now, 1_000L), 0, 50);
        v[1] = clip(data.combatTracker().cps(now, 5_000L), 0, 50);
        v[2] = clip(data.combatTracker().intervalCv(), 0, 5);
        v[3] = clip(data.combatTracker().intervalMean(), 0, 10_000);
        v[4] = clip(data.combatTracker().intervalStddev(), 0, 10_000);
        v[5] = clip(data.combatTracker().intervalEntropyBits(100), 0, 16);
        v[6] = clip(data.combatTracker().distinctTargets(now, 10_000), 0, 32);
        v[7] = clip(data.swingTracker().swingRate(now, 1_000L), 0, 50);

        // ---- rotation dynamics (RotationTracker)
        v[8] = clip(data.rotationTracker().angularDistanceCovered(now, 1_000L), 0, 5_000);
        v[9] = clip(data.rotationTracker().maxDeltaInWindow(now, 1_000L), 0, 180);
        v[10] = clip(data.rotationTracker().size(now), 0, 64);

        // ---- reach statistics (PlayerData)
        v[11] = clip(data.reachEma(), 0, 8);
        v[12] = clip(data.getReachSampleCount(), 0, 100_000);

        // ---- packet timing (PlayerData / MovementTracker)
        v[13] = clip(data.getPacketsPerSecond(), 0, 400);
        v[14] = clip(data.movementTracker().rawMovePacketsPerSecond(now), 0, 400);
        v[15] = clip(data.movementTracker().movePacketsPerSecond(now), 0, 400);
        v[16] = clip(data.movementTracker().airborneMillis(now), 0, 3_600_000);

        // ---- mining (BlockActionTracker); -1 (no history) maps to 0, the schema floor
        long breakInterval = data.blockActionTracker().breakInterval(now);
        v[17] = clip(breakInterval < 0L ? 0.0D : breakInterval, 0, 60_000);
        v[18] = clip(data.blockActionTracker().getImpossibleBreakStreak(), 0, 100);

        // ---- context (passed in by the caller)
        v[19] = clip(context.pingMillis(), 0, 5_000);
        v[20] = clip(context.tps1m(), 0, 20);
        v[21] = context.onGround() ? 1.0D : 0.0D;

        return new AiFeatureSnapshot(now, v);
    }

    private static double clip(double value, double lo, double hi) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return lo; // unknown measurement ⇒ floor of the documented range, never a guess
        }
        return Math.min(hi, Math.max(lo, value));
    }
}
