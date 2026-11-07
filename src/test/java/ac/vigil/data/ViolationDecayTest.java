package ac.vigil.data;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic violation-ledger tests with a fully controlled clock.
 *
 * <p>Motivation (Phase 4): combat VL evidence is evaluated synchronously at
 * event time — this is the "transient evidence" the plugin must not miss —
 * and then decays 5 s later at {@code decay-per-second}. These tests pin that
 * behaviour with a fake clock so the VL lifecycle is proven, not inferred,
 * including for the combat checks whose VL used to read as 0 at RCON time
 * (the observed zeros were the attack-event funnel starving the checks, not
 * decay racing the read — decay cannot even start until 5 s after the LAST
 * violation, which is after the scenario ends).</p>
 */
class ViolationDecayTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private AtomicLong now;
    private PlayerData data;

    @BeforeEach
    void setUp() {
        now = new AtomicLong(1_000_000L);
        data = new PlayerData(PLAYER, now::get);
    }

    @Test
    void violationIsVisibleImmediatelyAtEventTime() {
        double vl = data.addViolation("auto-clicker", 1.5D, 20.0D);
        assertEquals(1.5D, vl, 1.0E-9);
        assertEquals(1.5D, data.getViolationLevel("auto-clicker"), 1.0E-9);
        Map<String, Double> snapshot = data.getViolationLevelsSnapshot();
        assertEquals(1.5D, snapshot.get("auto-clicker"), 1.0E-9);
    }

    @Test
    void vlDoesNotDecayBeforeTheDelayElapses() {
        data.addViolation("kill-aura", 4.0D, 20.0D);
        now.addAndGet(4_999L); // just before the 5 s delay
        boolean changed = data.decay(1.0D, 5_000L);
        assertFalse(changed, "no decay may happen before the delay has elapsed");
        assertEquals(4.0D, data.getViolationLevel("kill-aura"), 1.0E-9);
    }

    @Test
    void vlDecaysOnlyAfterTheDelayElapses() {
        data.addViolation("kill-aura", 4.0D, 20.0D);
        now.addAndGet(5_000L); // exactly at the delay boundary
        boolean changed = data.decay(1.0D, 5_000L);
        assertTrue(changed);
        assertEquals(3.0D, data.getViolationLevel("kill-aura"), 1.0E-9);

        now.addAndGet(1_000L);
        data.decay(1.0D, 5_000L);
        assertEquals(2.0D, data.getViolationLevel("kill-aura"), 1.0E-9);
    }

    @Test
    void decayNeverDropsBelowZero() {
        data.addViolation("speed", 0.5D, 25.0D);
        now.addAndGet(6_000L);
        data.decay(1.0D, 5_000L);
        data.decay(1.0D, 5_000L);
        data.decay(1.0D, 5_000L);
        assertEquals(0.0D, data.getViolationLevel("speed"), 1.0E-9);
    }

    @Test
    void freshViolationRearmsTheDecayDelay() {
        data.addViolation("timer", 3.0D, 25.0D);
        now.addAndGet(4_000L);           // 4 s in: still inside the delay
        data.addViolation("timer", 2.0D, 25.0D); // fresh evidence re-arms the delay
        now.addAndGet(4_000L);           // 8 s total, but only 4 s since the fresh violation
        boolean changed = data.decay(1.0D, 5_000L);
        assertFalse(changed, "a fresh violation must re-arm the decay delay");
        assertEquals(5.0D, data.getViolationLevel("timer"), 1.0E-9);
    }

    @Test
    void transientEvidenceIsJudgedAtEventTimeNotAtReadTime() {
        // Simulates the auto-clicker flow: attacks land over 12 s, the LAST
        // attack is 1.5 s before the RCON read (bot.js captures vlText at
        // scenario end). Decay delay is 5 s — decay physically cannot have
        // started by the read, so any zero VL at read time is the event funnel,
        // never decay.
        long attackAt = now.get();
        for (int i = 0; i < 12; i++) {
            data.addViolation("auto-clicker", 1.0D, 20.0D);
            now.addAndGet(1_000L);
            attackAt = now.get();
        }
        now.addAndGet(1_500L); // scenario ends, vlText is read 1.5 s later
        assertTrue(now.get() - attackAt < 5_000L, "decay cannot have started inside the read window");
        assertTrue(data.getViolationLevel("auto-clicker") > 0.0D,
                "VL must still be fully visible inside the decay-delay window");
    }
}
