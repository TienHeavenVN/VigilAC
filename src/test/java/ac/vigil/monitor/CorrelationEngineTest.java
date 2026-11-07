package ac.vigil.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class CorrelationEngineTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private PlayerMonitor monitor;
    private final CorrelationEngine engine = new CorrelationEngine();

    @BeforeEach
    void setUp() {
        monitor = new PlayerMonitor(now::get);
    }

    private MoveSample.Builder sample() {
        return MoveSample.builder().at(0.0D, 64.0D, 0.0D)
                .worldName("world").gameMode("SURVIVAL").timestamp(now.get());
    }

    @Test
    void recentTeleportZeroesConfidence() {
        monitor.noteMove(sample().onGround(true).build());
        now.addAndGet(500);
        monitor.noteTeleport("test");
        CorrelationResult result = engine.correlateNoFall(monitor, 6.0D, 3.5D);
        assertEquals(0.0D, result.confidence());
        assertFalse(result.suppressing().isEmpty(), "teleport must appear as suppressing evidence");
    }

    @Test
    void recentVelocityZeroesConfidence() {
        now.addAndGet(100);
        monitor.noteVelocity(0.6D);
        CorrelationResult result = engine.correlateNoFall(monitor, 5.0D, 3.5D);
        assertEquals(0.0D, result.confidence());
        assertTrue(result.suppressing().stream().anyMatch(s -> s.contains("velocity")));
    }

    @Test
    void recentFallDamageZeroesConfidence() {
        now.addAndGet(100);
        monitor.noteDamage(true);
        CorrelationResult result = engine.correlateNoFall(monitor, 5.5D, 3.5D);
        assertEquals(0.0D, result.confidence());
    }

    @Test
    void cleanFallHasSupportingFactsAndPositiveConfidence() {
        monitor.noteMove(sample().onGround(true).build());
        for (int i = 0; i < 3; i++) {
            monitor.noteNoFallSuspiciousLanding("descent=5.0");
        }
        CorrelationResult result = engine.correlateNoFall(monitor, 5.25D, 3.5D);
        assertTrue(result.confidence() > 0.0D, "clean deep fall must carry confidence");
        assertTrue(result.supporting().stream().anyMatch(s -> s.contains("descent")));
        assertTrue(result.supporting().stream().anyMatch(s -> s.contains("suspicious landing")));
        assertTrue(result.suppressing().isEmpty());
    }

    @Test
    void unrelatedDamageOlderThanWindowStillSupports() {
        // Generic damage 20s ago (beyond RECENT_WINDOW_MILLIS) must not suppress anything.
        monitor.noteDamage(false);
        now.addAndGet(CorrelationEngine.RECENT_WINDOW_MILLIS + 5_000L);
        CorrelationResult result = engine.correlateNoFall(monitor, 6.0D, 3.5D);
        assertTrue(result.confidence() > 0.0D);
        assertTrue(result.suppressing().isEmpty());
    }
}

class CorrelationStatusTest {

    @org.junit.jupiter.api.Test
    void classificationTriState() {
        assertEquals(ac.vigil.monitor.CorrelationStatus.CONFIRMED,
                ac.vigil.monitor.CorrelationResult.classify(java.util.List.of("a", "b"), java.util.List.of()));
        assertEquals(ac.vigil.monitor.CorrelationStatus.SUSPECTED,
                ac.vigil.monitor.CorrelationResult.classify(java.util.List.of("a"), java.util.List.of()));
        assertEquals(ac.vigil.monitor.CorrelationStatus.INCONCLUSIVE,
                ac.vigil.monitor.CorrelationResult.classify(java.util.List.of(), java.util.List.of()));
        // suppression always wins, even with many supporting signals
        assertEquals(ac.vigil.monitor.CorrelationStatus.INCONCLUSIVE,
                ac.vigil.monitor.CorrelationResult.classify(java.util.List.of("a", "b", "c"), java.util.List.of("teleport")));
    }

    @org.junit.jupiter.api.Test
    void resultCarriesStatus() {
        ac.vigil.monitor.CorrelationResult r = new ac.vigil.monitor.CorrelationResult(
                0.5D, java.util.List.of("descent", "streak"), java.util.List.of());
        assertEquals(ac.vigil.monitor.CorrelationStatus.CONFIRMED, r.status());
        ac.vigil.monitor.CorrelationResult r2 = new ac.vigil.monitor.CorrelationResult(
                0.0D, java.util.List.of("descent", "streak"), java.util.List.of("fall damage"));
        assertEquals(ac.vigil.monitor.CorrelationStatus.INCONCLUSIVE, r2.status());
    }
}

class CorrelationMonitorPropagationTest {

    @org.junit.jupiter.api.Test
    void correlationStatusPropagatesIntoDetectionStatus() {
        java.util.concurrent.atomic.AtomicLong now = new java.util.concurrent.atomic.AtomicLong(1_000_000L);
        PlayerMonitor monitor = new PlayerMonitor(now::get);
        monitor.noteCorrelation("no-fall", CorrelationStatus.CONFIRMED);
        DetectionStatus status = monitor.status("no-fall", 1.0D, 10.0D, true);
        assertEquals("confirmed", status.correlationStatus());

        DetectionStatus other = monitor.status("fly", 0.0D, 10.0D, true);
        assertEquals("inconclusive", other.correlationStatus(), "checks never correlated report inconclusive");
    }
}
