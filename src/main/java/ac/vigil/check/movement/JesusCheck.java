package ac.vigil.check.movement;

import ac.vigil.check.Check;
import ac.vigil.check.CheckCategory;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.data.tracking.MovementTracker;
import ac.vigil.detection.DetectionContext;

/**
 * Detects walking or standing on water without Frost Walker.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>An observation qualifies when the feet block is dry
 *       ({@code inLiquid == false}) while the block directly below is water
 *       ({@code DetectionContext#isOnWaterSurface}). Vanilla physics sinks any
 *       player whose feet are above water without a supporting block or the
 *       Frost Walker enchantment.</li>
 *   <li>Consecutive qualifying observations are counted in the per-player
 *       tracker. A single qualifying observation is never enough: one tick can
 *       race with water placement or edge-of-block rounding, so evidence is
 *       only produced once the streak reaches {@code minStreak} (default 14,
 *       i.e. ~0.7 s of sustained floating — far beyond a jump arc's dry-feet
 *       window over a water surface).</li>
 *   <li>Once the streak holds, every further qualifying observation adds
 *       {@code vlPerTick} (default 0.5). The streak resets the moment a tick
 *       disqualifies (entering liquid, leaving the surface, teleport/velocity
 *       grace, Frost Walker appearing, low TPS).</li>
 * </ol>
 *
 * <h2>False-positive considerations</h2>
 * <ul>
 *   <li>Frost Walker (any level) is a full exemption — vanilla water walking.</li>
 *   <li>Vehicles (boats), elytra, Levitation and Slow Falling are exempt.</li>
 *   <li>{@code inLiquid} resets the streak, so swimming or jumping <i>through</i>
 *       water never accumulates evidence.</li>
 *   <li>Knockback/teleport grace resets the streak (boat splashes and piston
 *       bounces land players on the surface edge for one tick).</li>
 *   <li>Riptide trident motion is a bounded, evidence-based exemption
 *       (isRiptiding, a server-side state — never inferred from speed alone):
 *       a riptiding player skips across water by design.</li>
 *   <li>The mace Wind Burst impulse gets a bounded (≤3 s) grace set only on a
 *       <em>vertical-dominant</em> server-set velocity event (|vy| &gt; 0.45
 *       and &gt; 60% of total speed) — lateral knockback never triggers it,
 *       and it expires quickly, so it cannot launder sustained water walking.</li>
 *   <li>Jump arcs over a water body: each arc leaves the feet dry over water,
 *       but falling back into the water resets the streak via {@code inLiquid}.
 *       Chaining arcs without once dipping the feet into the water is not
 *       possible in vanilla physics, so legitimate jumpers never reach the
 *       default streak of 14.</li>
 *   <li>Waterlogged top slabs/stairs are NOT water at the feet-relative-below
 *       lookup used here only when the collision shape fills the block; a
 *       waterlogged slab still reads as WATER material — documented limitation:
 *       standing on a waterlogged slab over water counts as a surface tick.
 *       Rare enough that the {@code minStreak} gate keeps it harmless.</li>
 * </ul>
 */
public final class JesusCheck implements Check<DetectionContext> {

    public static final double MIN_TPS = 10.0D;

    private final int minStreak;
    private final double vlPerTick;

    public JesusCheck(int minStreak, double vlPerTick) {
        this.minStreak = minStreak;
        this.vlPerTick = vlPerTick;
    }

    @Override
    public String getName() {
        return "jesus";
    }

    @Override
    public CheckCategory getCategory() {
        return CheckCategory.MOVEMENT;
    }

    @Override
    public void evaluate(DetectionContext ctx, PlayerData data, ViolationCallback callback) {
        MovementTracker tracker = data.movementTracker();

        if (ctx.isInVehicle() || ctx.isGliding() || ctx.isInLiquid()
                || ctx.isTeleportGrace() || ctx.isKnockbackGrace()
                || ctx.isLevitation() || ctx.isSlowFalling()
                || ctx.hasFrostWalker()
                || ctx.isRiptiding()
                || ctx.isWindBurstGrace()
                || ctx.getServerTps() < MIN_TPS) {
            tracker.resetWaterSurface();
            return;
        }

        if (!ctx.isOnWaterSurface()) {
            tracker.resetWaterSurface();
            return;
        }

        int streak = tracker.noteWaterSurface();
        if (streak < minStreak) {
            return;
        }

        String debug = String.format(java.util.Locale.US,
                "waterStreak=%d horizontal=%.3f onGround=%b ping=%d tps=%.2f",
                streak, ctx.getHorizontalDistance(), ctx.isOnGround(),
                ctx.getPingMillis(), ctx.getServerTps());
        callback.onViolation(this, vlPerTick, debug);
    }
}
