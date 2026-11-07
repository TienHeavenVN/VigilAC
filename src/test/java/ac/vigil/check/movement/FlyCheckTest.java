package ac.vigil.check.movement;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

class FlyCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FlyCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new FlyCheck(3_000L, 0.6D, 10, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext airborne(double dy, long timestamp, boolean levitation, boolean slowFalling,
                                      boolean gliding, boolean vehicle, boolean inLiquid, double tps) {
        double y = 80.0D; // position irrelevant for the check math; dy drives it
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, y, 0.0D)
                .to(0.0D, y + dy, 0.0D)
                .onGround(false)
                .gliding(gliding)
                .inVehicle(vehicle)
                .inLiquid(inLiquid)
                .levitation(levitation)
                .slowFalling(slowFalling)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    private DetectionContext grounded(long timestamp, double tps) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 64.0D, 0.0D)
                .to(0.5D, 64.0D, 0.0D)
                .onGround(true)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void flagsSustainedHover() {
        long now = 1_000_000L;
        for (int i = 0; i < 90; i++) { // 4.5 s airborne at constant altitude
            now += 50L;
            check.evaluate(airborne(0.0D, now, false, false, false, false, false, 20.0D), data, sink());
        }
        assertFalse(flagged.isEmpty(), "hovering 4.5s above ground must flag");
    }

    @Test
    void jumpArcNeverFlags() {
        long now = 1_000_000L;
        double[] dyPattern = {0.42D, 0.35D, 0.22D, 0.1D, -0.04D, -0.18D, -0.32D, -0.46D, -0.6D};
        for (int rep = 0; rep < 3; rep++) { // repeated jumps, grounded between
            for (double dy : dyPattern) {
                now += 50L;
                check.evaluate(airborne(dy, now, false, false, false, false, false, 20.0D), data, sink());
            }
            now += 50L;
            check.evaluate(grounded(now, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "jump arcs must never flag, got " + flagged);
    }

    @Test
    void risingFlightFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 80; i++) { // 4 s of continuous rise
            now += 50L;
            check.evaluate(airborne(0.25D, now, false, false, false, false, false, 20.0D), data, sink());
        }
        assertFalse(flagged.isEmpty(), "sustained rising flight must flag");
    }

    @Test
    void levitationAndSlowFallingExempt() {
        long now = 1_000_000L;
        for (int i = 0; i < 90; i++) {
            now += 50L;
            check.evaluate(airborne(0.2D, now, true, false, false, false, false, 20.0D), data, sink());
        }
        now = 2_000_000L;
        for (int i = 0; i < 90; i++) {
            now += 50L;
            check.evaluate(airborne(0.0D, now, false, true, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "Levitation/Slow Falling must suppress fly evidence");
    }

    @Test
    void lowTpsSuppresses() {
        long now = 1_000_000L;
        for (int i = 0; i < 90; i++) {
            now += 50L;
            check.evaluate(airborne(0.0D, now, false, false, false, false, false, 8.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "below 10 TPS the check must suppress itself");
    }

    @Test
    void fallingDoesNotFlag() {
        long now = 1_000_000L;
        for (int i = 0; i < 90; i++) { // long descent, accelerating
            now += 50L;
            check.evaluate(airborne(-Math.min(3.9D, 0.1D + i * 0.05D), now, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "falling at terminal velocity is not flying");
    }
}

class FlyLowGradientSinkTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FlyCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        check = new FlyCheck(3_000L, 0.6D, 10, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext airborne(double dy, long timestamp, double tps) {
        double y = 80.0D;
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, y, 0.0D)
                .to(0.0D, y + dy, 0.0D)
                .onGround(false)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void sustainedLowGradientDescentFlags() {
        // 0.03 blocks per observation for 90 observations (~4.5s): impossible
        // under vanilla gravity, classic "slow sink" fly. Net = -2.7, which
        // defeats the pure-hover branch.
        long now = 1_000_000L;
        for (int i = 0; i < 90; i++) {
            now += 50L;
            check.evaluate(airborne(-0.03D, now, 20.0D), data, sink());
        }
        assertFalse(flagged.isEmpty(), "sustained sub-0.04 b/tick descent over 40+ ticks is fly evidence");
    }

    @Test
    void briefLowGradientDescentNeverFlags() {
        // Only 20 observations of low-gradient descent: below the tick minimum.
        long now = 1_000_000L;
        for (int i = 0; i < 20; i++) {
            now += 50L;
            check.evaluate(airborne(-0.03D, now, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "short low-gradient windows are not fly evidence");
    }
}
