package ac.vigil.config;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ConfigValidatorTest {

    @Test
    void coherentLaddersPass() {
        List<String> problems = ConfigValidator.validateLadder("speed", 5.0D, 10.0D, 20.0D, 25.0D);
        assertTrue(problems.isEmpty(), () -> "coherent ladder must pass: " + problems);
    }

    @Test
    void kicksDisabledWithZeroIsLegal() {
        List<String> problems = ConfigValidator.validateLadder("reach", 5.0D, 10.0D, 0.0D, 25.0D);
        assertTrue(problems.isEmpty(), () -> "kick-vl <= 0 disables kicks and is legal: " + problems);
    }

    @Test
    void warnBelowAlertIsRejected() {
        List<String> problems = ConfigValidator.validateLadder("fly", 10.0D, 5.0D, 20.0D, 25.0D);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("warn-vl")));
    }

    @Test
    void kickBelowWarnIsRejected() {
        List<String> problems = ConfigValidator.validateLadder("timer", 5.0D, 10.0D, 7.0D, 25.0D);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("kick-vl")));
    }

    @Test
    void maxBelowWarnIsRejected() {
        List<String> problems = ConfigValidator.validateLadder("scaffold", 5.0D, 10.0D, 15.0D, 8.0D);
        assertFalse(problems.isEmpty());
        assertTrue(problems.stream().anyMatch(p -> p.contains("max-vl")));
    }

    @Test
    void nanAndInfinityAreRejected() {
        assertFalse(ConfigValidator.validateLadder("x", Double.NaN, 10.0D, 20.0D, 25.0D).isEmpty());
        assertFalse(ConfigValidator.validateLadder("x", 5.0D, Double.POSITIVE_INFINITY, 20.0D, 25.0D).isEmpty());
    }

    @Test
    void validateAllAggregatesAcrossChecks() {
        Map<String, double[]> ladders = Map.of(
                "good", new double[] {5.0D, 10.0D, 20.0D, 25.0D},
                "bad", new double[] {10.0D, 5.0D, 20.0D, 25.0D});
        List<String> problems = ConfigValidator.validateAll(ladders);
        assertEquals(1, problems.size());
        assertTrue(problems.get(0).startsWith("bad"));
    }
}
