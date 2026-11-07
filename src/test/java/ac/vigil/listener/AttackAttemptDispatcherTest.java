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
}
