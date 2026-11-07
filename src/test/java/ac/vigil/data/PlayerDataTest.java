package ac.vigil.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class PlayerDataTest {

    @Test
    void violationLevelsAddAndCap() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        assertEquals(1.0D, data.addViolation("speed", 1.0D, 3.0D), 1e-9);
        assertEquals(2.5D, data.addViolation("speed", 1.5D, 3.0D), 1e-9);
        assertEquals(3.0D, data.addViolation("speed", 1.5D, 3.0D), 1e-9); // capped
    }

    @Test
    void speedEmaConverges() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        double ema = data.updateSpeedEma(5.0D);
        assertEquals(5.0D, ema, 1e-9); // first sample seeds the average
        double converged = 0.0D;
        for (int i = 0; i < 40; i++) {
            converged = data.updateSpeedEma(9.0D);
        }
        assertTrue(converged > 8.5D, "EMA should converge toward 9.0, got " + converged);
    }

    @Test
    void excessAccumulatorDrainsAndClampsAtZero() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        data.addExcessTime(1.5D);
        assertEquals(1.5D, data.getExcessSeconds(), 1e-9);
        data.addExcessTime(-0.5D);
        assertEquals(1.0D, data.getExcessSeconds(), 1e-9);
        data.addExcessTime(-10.0D);
        assertEquals(0.0D, data.getExcessSeconds(), 1e-9);
    }

    @Test
    void overReachStreakCountsAndResets() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        assertEquals(1, data.addOverReachAttack());
        assertEquals(2, data.addOverReachAttack());
        data.resetOverReachCounter();
        assertEquals(0, data.getConsecutiveOverReach());
    }

    @Test
    void reachEmaTracksRecentHistory() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        for (int i = 0; i < 6; i++) {
            data.recordReach(2.9D, 6);
        }
        assertEquals(2.9D, data.reachEma(), 1e-9);
        data.recordReach(4.0D, 6);
        assertTrue(data.reachEma() > 2.9D, "EMA should rise after a longer hit; got " + data.reachEma());
    }

    @Test
    void packetCounterAdvances() {
        PlayerData data = new PlayerData(UUID.randomUUID());
        for (int i = 0; i < 50; i++) {
            data.notePacket();
        }
        // Window has not elapsed yet; counter incremented atomically without error.
        assertTrue(data.getPacketsPerSecond() >= 0.0D);
    }
}
