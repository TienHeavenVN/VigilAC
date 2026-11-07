package ac.vigil.check.movement;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.DetectionContext;

class InvalidMovementCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private InvalidMovementCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new InvalidMovementCheck(4.25D, 4.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private DetectionContext move(double fromY, double toY, boolean gliding, boolean levitation) {
        return DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, fromY, 0.0D)
                .to(0.0D, toY, 0.0D)
                .gliding(gliding)
                .levitation(levitation)
                .serverTps(20.0D)
                .timestamp(System.currentTimeMillis())
                .build();
    }

    @Test
    void nonFiniteGeometryFlagsImmediately() {
        DetectionContext bad = DetectionContext.builder()
                .player(PLAYER)
                .from(0.0D, Double.NaN, 0.0D)
                .to(0.0D, 64.0D, 0.0D)
                .serverTps(20.0D)
                .build();
        check.evaluate(bad, data, sink());
        assertEquals(1, flagged.size());
    }

    @Test
    void terminalVelocityFallDoesNotFlag() {
        check.evaluate(move(100.0D, 96.5D, false, false), data, sink()); // 3.5 blocks/tick: legal terminal velocity
        assertTrue(flagged.isEmpty());
    }

    @Test
    void impossibleVerticalJumpFlags() {
        check.evaluate(move(100.0D, 106.0D, false, false), data, sink()); // +6 in one tick
        assertEquals(1, flagged.size());
    }

    @Test
    void glidingAndLevitationExempt() {
        check.evaluate(move(100.0D, 110.0D, true, false), data, sink()); // elytra
        check.evaluate(move(100.0D, 110.0D, false, true), data, sink()); // levitation
        assertTrue(flagged.isEmpty());
    }
}
