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

class TimerCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private TimerCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new TimerCheck(21.5D, 3_000L, 2.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext packetRate(double rate, long timestamp, double tps) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, 64.0D, 0.0D)
                .to(1.0D, 64.0D, 0.0D)
                .movePacketsPerSecond(rate)
                .serverTps(tps)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void vanillaRateNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 200; i++) { // 10 s at exactly 20/s
            now += 50L;
            check.evaluate(packetRate(20.0D, now, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty());
    }

    @Test
    void sustainedAccelerationFlagsOnlyAfterSustainWindow() {
        long now = 1_000_000L;
        for (int i = 0; i < 40; i++) { // 2 s of acceleration: below the 3 s sustain gate
            now += 50L;
            check.evaluate(packetRate(25.0D, now, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "excess under 3 s must not flag");
        for (int i = 0; i < 40; i++) { // past 3 s
            now += 50L;
            check.evaluate(packetRate(25.0D, now, 20.0D), data, sink());
        }
        assertFalse(flagged.isEmpty(), "sustained 25/s for >4 s must flag");
    }

    @Test
    void briefSpikesNeverFlag() {
        long now = 1_000_000L;
        for (int i = 0; i < 60; i++) {
            now += 50L;
            double rate = (i % 10 < 4) ? 30.0D : 15.0D; // bursty average, below sustain
            check.evaluate(packetRate(rate, now, 20.0D), data, sink());
        }
        assertTrue(flagged.isEmpty(), "bursty rate that resets the excess window must not flag");
    }

    @Test
    void lowTpsSuppresses() {
        long now = 1_000_000L;
        for (int i = 0; i < 120; i++) {
            now += 50L;
            check.evaluate(packetRate(30.0D, now, 7.0D), data, sink());
        }
        assertTrue(flagged.isEmpty());
    }
}
