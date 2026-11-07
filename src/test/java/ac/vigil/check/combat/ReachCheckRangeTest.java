package ac.vigil.check.combat;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.AttackContext;

/**
 * Regression tests for the Phase 7 reach remediation: item-specific range
 * (e.g. verified 1.21.11+ spear {@code attack_range} components) and the
 * opt-in, off-by-default latency compensation. Target boxes are placed so
 * eye-to-box distance equals exactly {@code distance} (eye height 1.62).
 */
class ReachCheckRangeTest {

    private static final UUID ATTACKER = UUID.randomUUID();

    private ReachCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new ReachCheck(3.0D, 0.03D, 4, 1.0D, 6);
        data = new PlayerData(ATTACKER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private AttackContext attack(double distance) {
        return attack(distance, null, false, 50L, 0.0D);
    }

    private AttackContext attack(double distance, Double attackRangeMax,
                                 boolean lagComp, long pingMillis, double velocityX) {
        AttackContext.Builder builder = AttackContext.builder()
                .attacker(ATTACKER)
                .targetType("ZOMBIE")
                .eye(0.0D, 1.62D, 0.0D)
                .box(-0.3D, 1.0D, distance, 0.3D, 2.0D, distance + 0.6D)
                .pingMillis((int) pingMillis)
                .targetVelocity(velocityX, 0.0D, 0.0D)
                .lagCompensationEnabled(lagComp)
                .lagCompensationMaxBlocks(1.0D);
        if (attackRangeMax != null) {
            builder.attackRangeMax(attackRangeMax).attackRangeSource("component");
        }
        return builder.build();
    }

    private void repeated(AttackContext context, int times) {
        for (int i = 0; i < times; i++) {
            check.evaluate(context, data, sink());
        }
    }

    @Test
    void verifiedItemRangeWidensCap() {
        // 4.2 blocks is over the default 3.03 cap but inside a 4.5 spear range.
        repeated(attack(4.2D, 4.5D, false, 50L, 0.0D), 8);
        assertTrue(flagged.isEmpty(), "a verified 4.5-block item range must not flag");
    }

    @Test
    void unknownItemRangeKeepsConfiguredCap() {
        repeated(attack(4.2D, null, false, 50L, 0.0D), 6);
        assertFalse(flagged.isEmpty(), "without a verified component the configured 3.0 cap applies");
    }

    @Test
    void reachPastVerifiedItemRangeStillFlags() {
        repeated(attack(4.8D, 4.5D, false, 50L, 0.0D), 6);
        assertFalse(flagged.isEmpty(), "exceeding even the item's verified range must flag");
    }

    @Test
    void latencyDoesNotGrantReachByDefault() {
        // High ping is handled by no slack at all in the default configuration.
        repeated(attack(3.2D, null, false, 2000L, 0.5D), 6);
        assertFalse(flagged.isEmpty(), "latency must not extend the default reach cap");
    }

    @Test
    void optInLagCompensationNarrowsDetection() {
        // 0.5 blocks/tick target velocity, 500 ms ping -> 0.25 block compensation.
        // Raw distance 3.20 - 0.25 = 2.95 blocks: within the default cap.
        repeated(attack(3.2D, null, true, 500L, 0.5D), 10);
        assertTrue(flagged.isEmpty(), "opt-in compensation: fast-moving-away target is legal");
    }

    @Test
    void optInLagCompensationIsCapped() {
        // 2.0 blocks/tick, 1000 ms ping would be 2.0 blocks; cap is 1.0.
        // 4.2 - 1.0 = 3.2 > 3.03: still over the cap and must flag.
        repeated(attack(4.2D, null, true, 1000L, 2.0D), 6);
        assertFalse(flagged.isEmpty(), "compensation is capped and can never rescue blatant reach");
    }

    @Test
    void itemRangeBeatsLatencySlack() {
        // Even with lag compensation off, a verified wider item range is authoritative.
        repeated(attack(4.4D, 4.5D, false, 2000L, 0.5D), 8);
        assertTrue(flagged.isEmpty());
    }
}
