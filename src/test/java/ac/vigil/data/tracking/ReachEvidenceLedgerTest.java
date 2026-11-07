package ac.vigil.data.tracking;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Contract tests for {@link ReachEvidenceLedger}: pairing, rejection
 * classification, bounding and confidence semantics. The ledger never
 * produces violation levels; these tests pin its evidence behavior.
 */
class ReachEvidenceLedgerTest {

    private ReachEvidenceLedger ledger;

    @BeforeEach
    void setUp() {
        ledger = new ReachEvidenceLedger();
    }

    @Test
    void overRangeUnpairedAttemptIsRejectedOnce() {
        ledger.noteResolved(1_000L, 42, 1L, 3.5D, null, "unknown", "DIAMOND_SWORD", "ZOMBIE");
        List<ReachEvidenceLedger.Entry> over = ledger.sweepRejected(10_000L, 400L, 3.03D);
        assertEquals(1, over.size(), "unpaired over-range attempt must surface as evidence");
        assertEquals(3.5D, over.get(0).getDistanceEyeToBox(), 1.0E-9);
        assertTrue(ledger.getRejectedCount() == 1L, "rejection is recorded");
        assertTrue(ledger.sweepRejected(20_000L, 400L, 3.03D).isEmpty(), "rejected entries are classified exactly once");
    }

    @Test
    void rejectedWithinRangeAttemptProducesNoEvidence() {
        ledger.noteResolved(1_000L, 42, 1L, 2.4D, null, "unknown", "DIAMOND_SWORD", "ZOMBIE");
        List<ReachEvidenceLedger.Entry> over = ledger.sweepRejected(10_000L, 400L, 3.03D);
        assertTrue(over.isEmpty(), "an honest miss within range is not reach evidence");
        assertTrue(ledger.getRejectedCount() == 1L, "it is still classified rejected");
    }

    @Test
    void damagePairingMarksAttemptDamagedAndSuppressesRejection() {
        ledger.noteResolved(1_000L, 42, 1L, 3.5D, null, "unknown", "DIAMOND_SWORD", "ZOMBIE");
        assertTrue(ledger.markDamaged(42, 1_100L, 400L), "damage event pairs with the attempt");
        assertTrue(ledger.sweepRejected(10_000L, 400L, 3.03D).isEmpty(),
                "paired attempts are hits, never rejected");
        assertEquals(1L, ledger.getDamagedCount());
    }

    @Test
    void damagePairsNewestUnmatchedAttemptFirst() {
        ledger.noteResolved(1_000L, 7, 1L, 3.5D, null, "unknown", "DIAMOND_SWORD", "ZOMBIE");
        ledger.noteResolved(1_200L, 7, 2L, 3.5D, null, "unknown", "DIAMOND_SWORD", "ZOMBIE");
        assertTrue(ledger.markDamaged(7, 1_300L, 400L));
        assertTrue(ledger.markDamaged(7, 1_300L, 400L));
        assertFalse(ledger.markDamaged(7, 1_300L, 400L), "no unmatched attempts remain");
        assertTrue(ledger.sweepRejected(10_000L, 400L, 3.03D).isEmpty());
    }

    @Test
    void invalidAttemptsCarryNoGeometryAndAreNeverEvidence() {
        ledger.noteInvalid(1_000L, 42, 1L, "AIR");
        assertTrue(ledger.sweepRejected(10_000L, 400L, 3.03D).isEmpty(),
                "invalid (unresolvable) attempts must never produce reach evidence");
        assertEquals(1L, ledger.getInvalidCount());
    }

    @Test
    void capacityIsBoundedAndDropsOldest() {
        long now = 1_000L;
        for (int i = 0; i < 100; i++) {
            ledger.noteResolved(now + i, i, i + 1L, 3.5D, null, "unknown", "STONE_SWORD", "ZOMBIE");
        }
        assertEquals(64, ledger.size(), "ledger is bounded");
        assertTrue(ledger.getOverflowDropCount() >= 36L, "overflow is counted, never silent");
        assertEquals(100L, ledger.getNoteCount());
    }

    @Test
    void outOfOrderAttemptsAreCounted() {
        ledger.noteResolved(1_000L, 1, 10L, 3.5D, null, "unknown", "A", "ZOMBIE");
        ledger.noteResolved(1_001L, 1, 9L, 3.5D, null, "unknown", "A", "ZOMBIE");
        ledger.noteResolved(1_002L, 1, 12L, 3.5D, null, "unknown", "A", "ZOMBIE");
        assertEquals(1L, ledger.getOutOfOrderCount());
    }

    @Test
    void itemSpecificRangeOverridesTheConfiguredCapForRejection() {
        // Distance 3.6 is over the default 3.03 cap, but under a 4.5-block spear cap.
        ledger.noteResolved(1_000L, 9, 1L, 3.6D, 4.5D, "component", "TRIDENT", "ZOMBIE");
        assertTrue(ledger.sweepRejected(10_000L, 400L, 3.03D).isEmpty(),
                "within the item's verified range: not evidence even when over the default cap");
        // And just past the spear cap it is evidence.
        ledger.noteResolved(1_100L, 9, 2L, 4.8D, 4.5D, "component", "TRIDENT", "ZOMBIE");
        assertEquals(1, ledger.sweepRejected(10_000L, 400L, 3.03D).size());
    }
}
