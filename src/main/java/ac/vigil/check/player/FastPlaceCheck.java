package ac.vigil.check.player;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.PlaceContext;

/**
 * Detects block placement faster than vanilla allows.
 *
 * <h2>Algorithm</h2>
 * <p>Since 1.18 block use has a 4-tick (200 ms) right-click cooldown, capping
 * legitimate placement at 5 blocks/s. The check counts placements in a
 * trailing window (default 2 s); when the rate exceeds
 * {@code maxPerSecond} (default 7.0, ~40% headroom for packet batching),
 * VL accrues scaled by the overflow.</p>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Only completed placements are counted; rejected/failed placements
 *       cannot inflate the rate.</li>
 *   <li>Burst-building at 6/s stays under the cap; only sustained machine
 *       rates flag.</li>
 * </ul>
 */
public final class FastPlaceCheck implements Check<PlaceContext> {

    private final double maxPerSecond;
    private final long windowMillis;
    private final double vlPerPlacement;

    public FastPlaceCheck(double maxPerSecond, long windowMillis, double vlPerPlacement) {
        this.maxPerSecond = maxPerSecond;
        this.windowMillis = windowMillis;
        this.vlPerPlacement = vlPerPlacement;
    }

    @Override
    public String getName() {
        return "fast-place";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.PLAYER;
    }

    @Override
    public void evaluate(PlaceContext ctx, PlayerData data, ViolationCallback callback) {
        long now = ctx.getTimestamp();
        int count = data.blockActionTracker().recordPlace(now, windowMillis);
        double perSecond = count * (1_000.0D / windowMillis);
        if (perSecond <= maxPerSecond) {
            return;
        }
        double amount = vlPerPlacement * Math.min(2.0D, perSecond / maxPerSecond);
        String debug = String.format(java.util.Locale.US,
                "placements=%d window=%dms rate=%.1f/s max=%.1f/s ping=%d",
                count, windowMillis, perSecond, maxPerSecond, ctx.getPingMillis());
        callback.onViolation(this, amount, debug);
    }
}
