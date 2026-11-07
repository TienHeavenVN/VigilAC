package ac.vigil.data.tracking;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FastBreakPenaltyTest {

    @Test
    void bucketDecaysExponentially() {
        FastBreakPenalty penalty = new FastBreakPenalty();
        penalty.setHalfLifeMillis(1_000.0D);
        penalty.addMissing(4_000.0D, 0L);
        double after = penalty.score(1_000L);
        assertEquals(2_000.0D, after, 1.0D, "one half-life halves the bucket");
        assertEquals(1_000.0D, penalty.score(2_000L), 1.0D);
    }

    @Test
    void tinyRemainderIsCleared() {
        FastBreakPenalty penalty = new FastBreakPenalty();
        penalty.setHalfLifeMillis(100.0D);
        penalty.addMissing(1.5D, 0L);
        assertEquals(1.5D, penalty.score(0L), 0.01D);
        assertEquals(0.0D, penalty.score(5_000L), 1e-9, "residual dust decays to zero");
    }

    @Test
    void dampenHalvesBucketAfterFlag() {
        FastBreakPenalty penalty = new FastBreakPenalty();
        penalty.addMissing(2_000.0D, 0L);
        penalty.dampen();
        assertEquals(1_000.0D, penalty.score(0L), 0.01D);
    }

    @Test
    void nonPositiveAndNaNInputsDoNotCorruptBucket() {
        FastBreakPenalty penalty = new FastBreakPenalty();
        penalty.addMissing(0.0D, 0L);
        penalty.addMissing(Double.NaN, 10L);
        penalty.addMissing(-500.0D, 20L);
        assertEquals(0.0D, penalty.score(20L), 1e-9);
    }
}
