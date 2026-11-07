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

class KillAuraCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private KillAuraCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new KillAuraCheck(1_000L, 3, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private AttackContext attackAt(String targetId, long timestamp) {
        return AttackContext.builder()
                .attacker(PLAYER)
                .targetUniqueId(targetId)
                .eye(0.0D, 66.0D, 0.0D)
                .box(-0.5D, 64.0D, 2.0D, 0.5D, 66.0D, 3.0D)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void threeDistinctTargetsWithMetronomicCadenceFlag() {
        // Anchor (3 distinct damaged targets) + independent regularity signal
        // (perfectly uniform 100 ms intervals) must flag.
        long now = 1_000_000L;
        String[] targets = {"zombie-1", "skeleton-1", "creeper-1", "zombie-2", "skeleton-2", "creeper-2", "zombie-3"};
        for (int i = 0; i < targets.length; i++) {
            now += 100L;
            data.combatTracker().recordAttack(now);
            check.evaluate(attackAt(targets[i], now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "anchor + metronomic cadence is aura evidence");
    }

    @Test
    void threeDistinctTargetsWithoutCorroborationDoNotFlag() {
        // Anchor alone must never flag: three distinct targets spread over a
        // second with irregular human cadence is normal melee behaviour.
        long now = 1_000_000L;
        long[] spacing = {140L, 620L, 210L};
        for (int i = 0; i < 3; i++) {
            now += spacing[i];
            data.combatTracker().recordAttack(now);
            check.evaluate(attackAt("target-" + i, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "anchor alone (no regularity, no packet rate) must not flag");
    }

    @Test
    void threeDistinctTargetsWithAttemptRateFlagEvenWithNoisyCadence() {
        // Simulate packetevents: attempts at high rate corroborate the anchor
        // even when the damage cadence itself is irregular.
        long now = 1_000_000L;
        String[] targets = {"a", "b", "c"};
        for (int i = 0; i < targets.length; i++) {
            now += 400L + (i * 137L) % 230L; // irregular
            for (int j = 0; j < 4; j++) {
                data.attackAttemptLedger().noteAttempt(now + j, 100 + i);
            }
            data.combatTracker().recordAttack(now);
            check.evaluate(attackAt(targets[i], now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "anchor + attack-attempt rate is aura evidence");
    }

    @Test
    void singleTargetNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            now += 200L;
            check.evaluate(attackAt("zombie-1", now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "attacking one entity repeatedly is normal combat");
    }

    @Test
    void distinctTargetsOutsideWindowDoNotAccumulate() {
        long now = 1_000_000L;
        check.evaluate(attackAt("zombie-1", now), data, sink());
        check.evaluate(attackAt("skeleton-1", now + 2_000L), data, sink());
        check.evaluate(attackAt("creeper-1", now + 4_000L), data, sink());
        assertTrue(flagged.isEmpty(), "targets seconds apart do not combine into an aura");
    }
}
