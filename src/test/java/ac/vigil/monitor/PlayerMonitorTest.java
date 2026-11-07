package ac.vigil.monitor;

import static org.junit.jupiter.api.Assertions.*;

import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class PlayerMonitorTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private PlayerMonitor monitor;

    @BeforeEach
    void setUp() {
        monitor = new PlayerMonitor(now::get);
    }

    private MoveSample.Builder sample() {
        return MoveSample.builder()
                .at(0.0D, 64.0D, 0.0D)
                .worldName("world")
                .gameMode("SURVIVAL")
                .pingMillis(20)
                .timestamp(now.get());
    }

    @Test
    void groundTransitionsEmitEvidence() {
        monitor.noteMove(sample().onGround(true).build());
        now.addAndGet(50);
        monitor.noteMove(sample().onGround(false).timestamp(now.get()).build());
        now.addAndGet(50);
        monitor.noteMove(sample().onGround(true).timestamp(now.get()).build());
        assertTrue(monitor.countEvidenceSince(EvidenceType.GROUND_EXIT, 0L) >= 1);
        assertTrue(monitor.countEvidenceSince(EvidenceType.LANDED, 0L) >= 1);
        assertTrue(monitor.isOnGround());
        assertTrue(monitor.lastLandingAt() > 0L);
    }

    @Test
    void damageCausesAreSeparated() {
        monitor.noteDamage(true);
        now.addAndGet(10);
        monitor.noteDamage(false);
        assertEquals(1_000_000L, monitor.lastFallDamageAt());
        assertEquals(1_000_010L, monitor.lastGenericDamageAt());
        assertEquals(1, monitor.countEvidenceSince(EvidenceType.DAMAGE_FALL, 0L));
        assertEquals(1, monitor.countEvidenceSince(EvidenceType.DAMAGE_GENERIC, 0L));
    }

    @Test
    void velocitiesAndPingAreExponentiallySmoothed() {
        monitor.noteMove(sample().at(0.0D, 64.0D, 0.0D).onGround(true).timestamp(now.get()).build());
        now.addAndGet(100);
        monitor.noteMove(sample().at(1.0D, 64.0D, 0.0D).onGround(true).timestamp(now.get()).build());
        assertTrue(monitor.velocityHorizontalEma() > 1.0D, "1 block in 100ms must register on the horizontal EMA");
        assertEquals(20.0D, monitor.pingEma(), 0.001D);
        monitor.noteMove(sample().at(1.0D, 64.0D, 0.0D).pingMillis(60).timestamp(now.get()).build());
        assertTrue(monitor.pingEma() > 20.0D);
    }

    @Test
    void worldChangeIsDetectedAndAged() {
        monitor.noteMove(sample().worldName("world").timestamp(now.get()).build());
        now.addAndGet(50);
        monitor.noteMove(sample().worldName("world_nether").timestamp(now.get()).build());
        assertEquals(1, monitor.countEvidenceSince(EvidenceType.WORLD_CHANGE, 0L));
        now.addAndGet(50);
        assertEquals(50L, monitor.ageOfLatest(EvidenceType.WORLD_CHANGE));
    }

    @Test
    void violationUpdatesStatusSignals() {
        monitor.noteViolation("speed", 5.0D, 5.0D);
        DetectionStatus status = monitor.status("speed", 5.0D, 5.0D, true);
        assertEquals(DetectionState.VIOLATION, status.state());
        assertEquals(1L, status.evidenceCount());
        assertEquals(monitor.nowMillis(), status.lastDetectionAt());
        assertTrue(status.confidence() > 0.0D);
    }

    @Test
    void suspectWhenBelowAlertAndClearAtZero() {
        monitor.noteViolation("speed", 2.0D, 5.0D);
        assertEquals(DetectionState.SUSPECT, monitor.status("speed", 2.0D, 5.0D, true).state());
        assertEquals(DetectionState.CLEAR, monitor.status("speed", 0.0D, 5.0D, true).state());
    }

    @Test
    void disabledCheckRendersDisabled() {
        monitor.noteViolation("speed", 100.0D, 5.0D);
        assertEquals(DetectionState.DISABLED, monitor.status("speed", 100.0D, 5.0D, false).state());
    }

    @Test
    void exemptionIsFreshThenStale() {
        monitor.noteExemption("fast-break", "instant-break material (vanilla lower bound <= 50ms)");
        DetectionStatus fresh = monitor.status("fast-break", 0.0D, 5.0D, true);
        assertEquals(DetectionState.EXEMPT, fresh.state());
        assertTrue(fresh.exemptionReason().contains("instant-break"));
        now.addAndGet(PlayerMonitor.EXEMPTION_FRESHNESS_MILLIS + 1);
        DetectionStatus stale = monitor.status("fast-break", 0.0D, 5.0D, true);
        assertEquals(DetectionState.CLEAR, stale.state(), "exemption must expire after the freshness window");
    }

    @Test
    void totalObservationsCounted() {
        for (int i = 0; i < 25; i++) {
            now.addAndGet(50);
            monitor.noteMove(sample().timestamp(now.get()).build());
        }
        assertEquals(25L, monitor.totalObservations());
    }
}
