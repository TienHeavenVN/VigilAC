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

class AimAssistCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private AimAssistCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new AimAssistCheck(45.0D, 6.0D, 120L, 4, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private AttackContext swingAt(double cx, double cz, float yaw, long timestamp, String targetId) {
        return AttackContext.builder()
                .attacker(PLAYER)
                .targetUniqueId(targetId)
                .eye(0.0D, 66.5D, 0.0D)
                .box(cx - 0.4D, 65.5D, cz - 0.4D, cx + 0.4D, 67.5D, cz + 0.4D) // box center vertically on the eye line (pitch 0)
                .yaw(yaw)
                .pitch(0.0F)
                .timestamp(timestamp)
                .build();
    }

    private void attack(AttackContext ctx) {
        data.combatTracker().recordAttack(ctx.getTimestamp());
        check.evaluate(ctx, data, sink());
    }

    @Test
    void repeatedCrosshairTeleportsOntoNewTargetsFlag() {
        long now = 1_000_000L;
        // MC look vector for yaw: (-sin(yaw), _, cos(yaw)). Each swing lands dead on a NEW target 90+ degrees away.
        double[][] targets = {{0.0D, 3.0D}, {-3.0D, 0.0D}, {0.0D, -3.0D}, {3.0D, 0.0D}, {1.93D, -2.30D}};
        float[] yaws = {0.0F, 90.0F, 180.0F, -90.0F, -140.0F};
        for (int i = 0; i < targets.length; i++) {
            now += 80L;
            attack(swingAt(targets[i][0], targets[i][1], yaws[i], now, "target-" + i));
        }
        assertFalse(flagged.isEmpty(), "consecutive large swings landing on fresh targets must flag");
    }

    @Test
    void smoothTrackingNeverFlags() {
        long now = 1_000_000L;
        // Smooth turn from yaw 40 to yaw 0 over 10 attacks: gradual re-aim, never a snap.
        for (int i = 0; i < 10; i++) {
            now += 100L;
            attack(swingAt(0.0D, 3.0D, 40.0F - i * 4.5F, now, "zombie-1"));
        }
        assertTrue(flagged.isEmpty(), "gradual human aim correction must never flag, got " + flagged);
    }

    @Test
    void snapsLandingAwayFromTargetDoNotFlag() {
        long now = 1_000_000L;
        // Huge swings every attack but the crosshair lands 180 degrees OFF the target.
        for (int i = 0; i < 10; i++) {
            now += 80L;
            attack(swingAt(0.0D, 3.0D, 180.0F, now, "zombie-1"));
        }
        assertTrue(flagged.isEmpty(), "big swings that miss the target are not aim evidence");
    }
}
