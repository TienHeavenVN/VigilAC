package ac.vigil.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * State-machine coverage for the per-check detection status view used by
 * {@code /vigilac monitor}.
 */
class DetectionStatusTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private PlayerMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new PlayerMonitor(now::get);
    }

    @Test
    void clearWithNoViolations() {
        DetectionStatus status = monitor.status("speed", 0.0D, 5.0D, true);
        assertEquals(DetectionState.CLEAR, status.state());
        assertEquals(0L, status.evidenceCount());
        assertEquals(0L, status.lastDetectionAt());
        assertEquals(0.0D, status.confidence());
    }

    @Test
    void escalatesClearSuspectViolationWithVl() {
        assertEquals(DetectionState.CLEAR, monitor.status("speed", 0.0D, 5.0D, true).state());
        monitor.noteViolation("speed", 1.0D, 5.0D);
        assertEquals(DetectionState.SUSPECT, monitor.status("speed", 1.0D, 5.0D, true).state());
        monitor.noteViolation("speed", 5.0D, 5.0D);
        assertEquals(DetectionState.VIOLATION, monitor.status("speed", 5.0D, 5.0D, true).state());
    }

    @Test
    void disabledOverridesEverything() {
        monitor.noteViolation("speed", 9.0D, 5.0D);
        assertEquals(DetectionState.DISABLED, monitor.status("speed", 9.0D, 5.0D, false).state());
    }

    @Test
    void exemptionBeatsSuspectAtZeroVlOnly() {
        monitor.noteExemption("fast-break", "instant-break material");
        assertEquals(DetectionState.EXEMPT, monitor.status("fast-break", 0.0D, 5.0D, true).state());
        // Once VL accumulates above zero the exemption no longer hides suspicion
        assertEquals(DetectionState.SUSPECT, monitor.status("fast-break", 2.0D, 5.0D, true).state());
    }

    @Test
    void lastEvidenceIsExposedPerCheck() {
        monitor.noteCheckEvidence("speed", "ema=6.1 cap=5.61 sustained=4 ticks");
        monitor.noteCheckEvidence("fly", "hover 41 ticks");
        DetectionStatus speed = monitor.status("speed", 0.0D, 5.0D, true);
        DetectionStatus fly = monitor.status("fly", 0.0D, 5.0D, true);
        assertEquals("ema=6.1 cap=5.61 sustained=4 ticks", speed.lastEvidenceDetail());
        assertEquals("hover 41 ticks", fly.lastEvidenceDetail());
        assertTrue(speed.lastEvidenceAt() > 0L);
        assertTrue(fly.lastEvidenceAt() >= speed.lastEvidenceAt());
        DetectionStatus other = monitor.status("no-fall", 0.0D, 5.0D, true);
        assertEquals("", other.lastEvidenceDetail());
        assertEquals(0L, other.lastEvidenceAt());
    }

    @Test
    void confidenceIsMonotoneTowardVlRatio() {
        monitor.noteViolation("speed", 5.0D, 5.0D); // target 1.0
        double first = monitor.status("speed", 5.0D, 5.0D, true).confidence();
        monitor.noteViolation("speed", 5.0D, 5.0D);
        double second = monitor.status("speed", 5.0D, 5.0D, true).confidence();
        assertTrue(second > first, "confidence must grow toward the VL/alert ratio with repeated detections");
        assertTrue(second <= 1.0D);
    }
}
