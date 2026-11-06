package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.detection.DetectionContext;

/**
 * Detects movement-packet rate accelerators (timer hacks) from the player
 * event stream.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Every {@code PlayerMoveEvent} — rotation-only or positional — is
 *       counted in a trailing 1-second ring (filled by the listener, see
 *       {@link DetectionContext#getMovePacketsPerSecond()}).</li>
 *   <li>Vanilla clients send position/rotation as a single combined packet
 *       at 20 per second, once per tick; sustained rates above the configured
 *       threshold (default 21.5) can only come from a timer accelerator or a
 *       lag-compensating client burst.</li>
 *   <li>The evidence must persist for {@code minExcessMillis} (default 3 s)
 *       before VL accrues, and every excess observation adds only
 *       {@code excessVlPerSecond * 0.05} VL, scaled by the overflow. A
 *       micro-burst can never produce an alert on its own.</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Rotation packets ride the same combined packet since 1.9; head-turn
 *       spam at high FPS does NOT inflate the count (one packet per tick).</li>
 *   <li>Low-TPS servers under-report movement events, so the check disables
 *       below 10 TPS to avoid both false negatives and evidence storms.</li>
 *   <li>Latency stretches packets in TIME (they arrive late, not fast) — it
 *       can only lower the measured rate, never inflate it.</li>
 * </ul>
 */
public final class TimerCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;

    private final double thresholdPerSecond;
    private final long minExcessMillis;
    private final double excessVlPerSecond;

    public TimerCheck(double thresholdPerSecond, long minExcessMillis, double excessVlPerSecond) {
        this.thresholdPerSecond = thresholdPerSecond;
        this.minExcessMillis = minExcessMillis;
        this.excessVlPerSecond = excessVlPerSecond;
    }

    @Override
    public String getName() {
        return "timer";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        MovementTracker tracker = data.movementTracker();

        if (ctx.getServerTps() < MIN_TPS) {
            tracker.resetTimerExcess();
            return;
        }

        long now = ctx.getTimestamp();
        double rate = ctx.getMovePacketsPerSecond();
        if (rate <= thresholdPerSecond) {
            tracker.resetTimerExcess();
            return;
        }

        long since = tracker.markTimerExcess(now);
        long excessMillis = now - since;
        if (excessMillis < minExcessMillis) {
            return; // not sustained yet
        }

        double over = rate - thresholdPerSecond;
        // Flat ~20 observations/s: amount matches "N VL per second of excess"
        // semantics, with a mild overflow multiplier capped at 2x.
        double scale = Math.min(2.0D, 1.0D + over / thresholdPerSecond);
        double amount = excessVlPerSecond * 0.05D * scale;
        String debug = String.format(java.util.Locale.US,
                "rate=%.1f/s threshold=%.1f excessFor=%dms ping=%d tps=%.2f",
                rate, thresholdPerSecond, excessMillis, ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, amount, debug);
    }
}
