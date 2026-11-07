package ac.vigil.check;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.movement.SpeedCheck;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

/**
 * Simulation tests for {@link SpeedCheck}: feeds synthetic movement sequences
 * into the check and asserts flag / no-flag outcomes for each scenario class.
 */
class SpeedCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private SpeedCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new SpeedCheck(4.4D, 5.8D, 0.2D, 1.6D, 2.0D, 1.2D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    /** Feeds synthetic move events with {@code speed} blocks/sec, 100 ms apart. */
    private void simulate(double speed, int samples, long startMillis, boolean sprint,
                          int speedPotionLevel, boolean vehicle, boolean gliding,
                          boolean inLiquid, boolean slippery, boolean teleportGrace,
                          boolean knockbackGrace, double tps) {
        long now = startMillis;
        double x = 0.0D;
        // First sample establishes the internal clock only.
        check.evaluate(ctx(0.0D, 0.0D, 64.0D, now, sprint, speedPotionLevel, vehicle, gliding,
                inLiquid, slippery, teleportGrace, knockbackGrace, tps), data, sink());
        for (int i = 0; i < samples; i++) {
            double dtSeconds = 0.1D; // 100 ms between position samples
            double nx = x + speed * dtSeconds;
            now += 100L;
            check.evaluate(ctx(x, nx, 64.0D, now, sprint, speedPotionLevel, vehicle, gliding,
                    inLiquid, slippery, teleportGrace, knockbackGrace, tps), data, sink());
            x = nx;
        }
    }

    private DetectionContext ctx(double fromX, double toX, double y, long timestamp,
                                 boolean sprint, int potion, boolean vehicle, boolean gliding,
                                 boolean inLiquid, boolean slippery, boolean teleportGrace,
                                 boolean knockbackGrace, double tps) {
        return DetectionContext.builder()
                .player(PLAYER)
                .worldName("world")
                .from(fromX, y, 0.0D)
                .to(toX, y, 0.0D)
                .yaw(0.0F)
                .pitch(0.0F)
                .onGround(true)
                .sprinting(sprint)
                .speedPotionLevel(potion)
                .inVehicle(vehicle)
                .gliding(gliding)
                .inLiquid(inLiquid)
                .onSlipperyBlock(slippery)
                .teleportGrace(teleportGrace)
                .knockbackGrace(knockbackGrace)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void honestSprintingPlayerNeverFlags() {
        simulate(5.6D, 100, 1_000L, true, 0, false, false, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "honest sprint must not flag, got: " + flagged);
    }

    @Test
    void honestWalkNeverFlags() {
        simulate(4.3D, 100, 1_000L, false, 0, false, false, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "honest walk must not flag, got: " + flagged);
    }

    @Test
    void blatantSpeedFlags() {
        // 8.5 blocks/sec while sprinting (no potions): well beyond the 5.8 cap.
        simulate(8.5D, 60, 1_000L, true, 0, false, false, false, false, false, false, 20.0D);
        assertFalse(flagged.isEmpty(), "sustained 8.5 b/s sprint must flag");
        double total = flagged.stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(total > 0.5D, "expected meaningful VL, got " + total);
    }

    @Test
    void speedPotionRaisesCapLegitimately() {
        // Speed II cap = 5.8 * 1.4 = 8.12; 8.0 b/s sprint is legal here.
        simulate(8.0D, 100, 1_000L, true, 2, false, false, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "sprint + Speed II must not flag, got: " + flagged);
    }

    @Test
    void teleportGraceSuspendsCheck() {
        simulate(12.0D, 30, 1_000L, true, 0, false, false, false, false, true, false, 20.0D);
        assertTrue(flagged.isEmpty(), "teleport grace must suppress detection");
        assertEquals(0.0D, data.getExcessSeconds(), 1e-9);
    }

    @Test
    void knockbackGraceSuspendsCheck() {
        simulate(10.0D, 30, 1_000L, false, 0, false, false, false, false, false, true, 20.0D);
        assertTrue(flagged.isEmpty(), "knockback grace must suppress detection");
    }

    @Test
    void vehicleSuspendsCheck() {
        simulate(30.0D, 30, 1_000L, false, 0, true, false, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "vehicles must not flag");
    }

    @Test
    void elytraSuspendsCheck() {
        simulate(35.0D, 30, 1_000L, false, 0, false, true, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "elytra flight must not flag");
    }

    @Test
    void liquidSuspendsCheck() {
        simulate(9.0D, 30, 1_000L, true, 0, false, false, true, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "liquid movement must not flag");
    }

    @Test
    void slipperyBlockRaisesCap() {
        // Slippery cap = 5.8 * 1.6 = 9.28; 9.0 b/s sprint is legal on ice.
        simulate(9.0D, 100, 1_000L, true, 0, false, false, false, true, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "sprint on ice must not flag, got: " + flagged);
    }

    @Test
    void lowTpsDisablesCheck() {
        simulate(9.0D, 30, 1_000L, true, 0, false, false, false, false, false, false, 8.0D);
        assertTrue(flagged.isEmpty(), "low TPS must disable the check");
    }

    @Test
    void briefBurstBelowMinSustainDoesNotFlag() {
        // 10 b/s for ~0.5 seconds (< 1.2s min sustain): must not flag.
        simulate(10.0D, 6, 1_000L, true, 0, false, false, false, false, false, false, 20.0D);
        assertTrue(flagged.isEmpty(), "short bursts below min-sustain must not flag");
    }
}
