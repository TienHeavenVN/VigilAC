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

class StepCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private StepCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new StepCheck(0.95D, 2, 2.5D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext rise(double fromY, double deltaY, long timestamp,
                                  boolean vehicle, boolean gliding, boolean inLiquid,
                                  boolean levitation, boolean slowFalling,
                                  boolean teleportGrace, boolean knockbackGrace, double tps) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, fromY, 0.0D)
                .to(0.0D, fromY + deltaY, 0.0D)
                .inVehicle(vehicle)
                .gliding(gliding)
                .inLiquid(inLiquid)
                .levitation(levitation)
                .slowFalling(slowFalling)
                .teleportGrace(teleportGrace)
                .knockbackGrace(knockbackGrace)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void vanillaAutoStepNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 40; i++) {
            now += 50L;
            check.evaluate(rise(63.0D, 0.55D, now, false, false, false, false, false, false, false, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "0.55 rises are vanilla auto-step territory");
    }

    @Test
    void firstImpossibleRiseIsSilentSecondFlags() {
        long now = 1_000_000L;
        now += 50L;
        check.evaluate(rise(63.0D, 1.05D, now, false, false, false, false, false, false, false, 20.0D), data, sink());
        assertTrue(flagged.isEmpty(), "a single impossibility can race with block placement; streak gate holds");
        now += 50L;
        check.evaluate(rise(64.0D, 1.05D, now, false, false, false, false, false, false, false, 20.0D), data, sink());
        assertEquals(1, flagged.size(), "second consecutive impossibility produces VL");
        assertEquals(2.5D, flagged.get(0), 1e-9);
    }

    @Test
    void legalTickBetweenImpossibilitiesResetsStreak() {
        long now = 1_000_000L;
        now += 50L;
        check.evaluate(rise(63.0D, 1.05D, now, false, false, false, false, false, false, false, 20.0D), data, sink());
        now += 50L;
        check.evaluate(rise(64.0D, 1.05D, now, false, false, false, false, false, false, false, 20.0D), data, sink());
        flagged.clear();
        now += 50L;
        check.evaluate(rise(65.0D, 0.0D, now, false, false, false, false, false, false, false, 20.0D), data, sink()); // legal tick
        now += 50L;
        check.evaluate(rise(65.0D, 1.10D, now, false, false, false, false, false, false, false, 20.0D), data, sink()); // 1.10 fresh
        assertTrue(flagged.isEmpty(), "legal observation resets the impossibility streak");
    }

    @Test
    void pistonGraceSuppresses() {
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            now += 50L;
            boolean grace = (i % 2 == 0); // piston ticks alternate with grace on
            check.evaluate(rise(63.0D + i, 1.5D, now, false, false, false, false, false, false, grace, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "velocity/piston grace resets the streak every tick");
    }

    @Test
    void vehicleGlideLevitationAreExempt() {
        long now = 1_000_000L;
        boolean[][] modes = {
                {true, false, false},
                {false, true, false},
                {false, false, true},
        };
        for (boolean[] mode : modes) {
            PlayerData fresh = new PlayerData(UUID.randomUUID());
            List<Double> freshFlagged = new ArrayList<>();
            ViolationCallback freshSink = (c, vl, debug) -> freshFlagged.add(vl);
            long t = now;
            for (int i = 0; i < 10; i++) {
                t += 50L;
                check.evaluate(rise(63.0D + i, 1.5D, t, mode[0], mode[1], false, mode[2], false, false, false, 20.0D), fresh, freshSink);
            }
            assertTrue(freshFlagged.isEmpty(), "modifier state must exempt: " + java.util.Arrays.toString(mode));
        }
    }

    @Test
    void jumpBoostSixPlusDocumentedCeiling() {
        // Jump Boost VI: opening jump tick ~1.02 — below default 0.95? NO: 0.42+0.6=1.02 > 0.95.
        // This test pins the documented behavior: JB6 opening tick alone flags ONCE per jump
        // after the streak gate; a full arc produces at most one flag tick, not a cascade.
        long now = 1_000_000L;
        double[] arc = {1.02D, 0.95D, 0.60D, 0.30D, 0.05D, -0.15D, -0.35D};
        int flags = 0;
        for (double dy : arc) {
            now += 50L;
            check.evaluate(rise(63.0D, dy, now, false, false, false, false, false, false, false, 20.0D), data, sink());
            flags = flagged.size();
        }
        // 1.02 (streak 1, silent) then 0.95 (reset) — no flags on a single JB6 arc.
        assertEquals(0, flags, "a single Jump Boost VI arc never crosses the streak gate");
    }

    @Test
    void lowTpsSuppresses() {
        long now = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            now += 50L;
            check.evaluate(rise(63.0D + i, 1.5D, now, false, false, false, false, false, false, false, 9.5D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "below the TPS floor the check stays silent");
    }
}
