package ac.vigil.listener;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import java.util.logging.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;
import ac.vigil.detection.AttackAttemptSnapshot;
import ac.vigil.detection.AttackGeometryResolution;

/**
 * Dispatcher contract tests: bounding, overflow discipline, main-thread
 * resolution semantics and failure behavior. The stub resolver returns
 * {@code null} (entity not found path) so no geometry is needed.
 */
class AttackAttemptDispatcherTest {

    private PlayerDataManager dataManager;
    private AttackAttemptDispatcher dispatcher;
    private final UUID attacker = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        dataManager = new PlayerDataManager();
        dispatcher = new AttackAttemptDispatcher(dataManager, (a, entityId) -> null, Logger.getAnonymousLogger());
    }

    @Test
    void fullQueueDropsIncomingAndCountsOverflow() {
        dataManager.load(attacker);
        int submitted = AttackAttemptDispatcher.CAPACITY + 44;
        for (int i = 0; i < submitted; i++) {
            dispatcher.submit(AttackAttemptSnapshot.of(attacker, 1000 + i, System.currentTimeMillis(), "ATTACK"));
        }
        assertEquals(44L, dispatcher.getOverflowCount(), "excess attempts are dropped, never queued beyond capacity");
        assertEquals(AttackAttemptDispatcher.CAPACITY, dispatcher.getPendingCount());
        assertEquals((long) submitted, dispatcher.getSubmittedCount());
    }

    @Test
    void drainResolvesBoundedBatchAndRecordsInvalidForMissingEntities() {
        PlayerData data = dataManager.load(attacker);
        for (int i = 0; i < 10; i++) {
            dispatcher.submit(AttackAttemptSnapshot.of(attacker, 2000 + i, System.currentTimeMillis(), "INTERACT_ENTITY"));
        }
        dispatcher.drainTick();
        assertEquals(0, dispatcher.getPendingCount(), "a single drain resolves a small batch fully");
        assertEquals(10L, dispatcher.getInvalidCount(), "missing entities resolve to INVALID, never to reach VL");
        assertEquals(0L, dispatcher.getResolvedCount());
        assertEquals(10L, data.reachEvidenceLedger().getInvalidCount(), "invalid attempts land in the ledger with no geometry");
    }

    @Test
    void drainBudgetCapsResolutionWorkPerTick() {
        dataManager.load(attacker);
        for (int i = 0; i < 100; i++) {
            dispatcher.submit(AttackAttemptSnapshot.of(attacker, 3000 + i, System.currentTimeMillis(), "ATTACK"));
        }
        dispatcher.drainTick();
        assertEquals(36, dispatcher.getPendingCount(), "drain is bounded to 64 resolutions per tick");
        assertEquals(64L, dispatcher.getInvalidCount());
    }

    @Test
    void untrackedAttackerAttemptIsDroppedWithoutCrashing() {
        // No PlayerData for this attacker: nothing to evaluate, no conviction.
        dispatcher.submit(AttackAttemptSnapshot.of(UUID.randomUUID(), 4000, System.currentTimeMillis(), "ATTACK"));
        dispatcher.drainTick();
        assertEquals(1L, dispatcher.getInvalidCount());
    }

    @Test
    void overflowNeverProducesRejectedEvidence() {
        PlayerData data = dataManager.load(attacker);
        // Fill the queue entirely; overflow attempts must not appear anywhere.
        for (int i = 0; i < AttackAttemptDispatcher.CAPACITY + 10; i++) {
            dispatcher.submit(AttackAttemptSnapshot.of(attacker, 5000 + i, System.currentTimeMillis(), "ATTACK"));
        }
        dispatcher.drainTick();
        assertEquals(0L, data.reachEvidenceLedger().getResolvedCount());
        assertTrue(dispatcher.getOverflowCount() == 10L);
    }

    @Test
    void successfulHitNeverBecomesRejectedEvidenceWhileStaleAttemptDoes() {
        // Full pipeline, deterministic: the landed hit is paired (its damage is
        // processed BEFORE the deferred resolution drains), while a second,
        // stale over-range attempt from the same attacker is left unpaired.
        // The stale one outlives REJECT_AFTER_MILLIS and must be classified
        // rejected exactly once; the paired hit never may be.
        PlayerData data = dataManager.load(attacker);
        AttackGeometryResolution overRange = AttackGeometryResolution.resolved(
                0, 1.62, 0,
                0, 0, 5.0D,
                0.6D, 1.8D, 5.6D,
                "ZOMBIE", null, "unknown", "DIAMOND_SWORD");
        AttackAttemptDispatcher paired = new AttackAttemptDispatcher(dataManager, (a, id) -> overRange, Logger.getAnonymousLogger());
        long now = System.currentTimeMillis();
        paired.submit(AttackAttemptSnapshot.of(attacker, 777, now, "ATTACK"));
        paired.submit(AttackAttemptSnapshot.of(attacker, 778, now - 10_000L, "ATTACK")); // stale, never pairs
        paired.noteDamage(data, 777, now);                     // damage lands before resolution (deferred)
        paired.drainTick();                                    // resolves both attempts in FIFO order
        assertEquals(1L, data.reachEvidenceLedger().getDamagedCount(),
                "the landed hit is paired at resolution time");
        for (int i = 0; i < 20; i++) {
            paired.drainTick();                                // 20th tick runs the rejection sweep
        }
        assertEquals(1L, data.reachEvidenceLedger().getRejectedCount(),
                "only the unpaired stale attempt is rejected");
        assertEquals(1L, paired.getRejectedOverRangeCount(),
                "the stale over-range attempt is monitor evidence exactly once");
    }

    @Test
    void rejectBoundaryIsStrictlyAfterPairWindow() {
        assertTrue(AttackAttemptDispatcher.REJECT_AFTER_MILLIS > AttackAttemptDispatcher.PAIR_WINDOW_MILLIS,
                "rejection must not be possible while a damage could still pair");
    }
}
