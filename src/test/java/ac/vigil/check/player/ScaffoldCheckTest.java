package ac.vigil.check.player;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.PlaceContext;

class ScaffoldCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private ScaffoldCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new ScaffoldCheck(80.0D, 6, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private PlaceContext place(double feetX, double feetZ, int blockX, int blockY, int blockZ,
                               float yaw, float pitch, long timestamp) {
        return PlaceContext.builder()
                .player(PLAYER)
                .blockType("OAK_PLANKS")
                .block(blockX, blockY, blockZ)
                .feet(feetX, 64.0D, feetZ)
                .yaw(yaw)
                .pitch(pitch)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void placingHundredAndEightyDegreesFromViewFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            now += 200L;
            // Looking north (+Z, yaw 0) but placing behind the player (-Z).
            check.evaluate(place(0.0D, 0.0D, 0, 64, -1, 0.0F, 0.0F, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "six consecutive out-of-view placements must flag");
    }

    @Test
    void bridgingWhereLookingNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            now += 200L;
            // Looking at +Z and placing the block at +Z: normal bridge building.
            check.evaluate(place(0.0D, i, 0, 64, i + 1, 0.0F, -30.0F, now), data, sink());
        }
        assertTrue(flagged.isEmpty());
    }

    @Test
    void singleOutOfViewPlacementDoesNotFlag() {
        long now = 1_000_000L;
        check.evaluate(place(0.0D, 0.0D, 0, 64, -1, 0.0F, 0.0F, now), data, sink());
        assertTrue(flagged.isEmpty());
    }

    @Test
    void sneakLookDownPlacementBehindPlayerFlagsButInViewDoesNot() {
        long now = 1_000_000L;
        // Looking straight down (pitch -90) and placing beside the feet: 90 degrees off view.
        for (int i = 0; i < 6; i++) {
            now += 200L;
            check.evaluate(place(0.0D, 0.0D, 1, 64, 0, 0.0F, -90.0F, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "looking straight down while placing beside you exceeds the angle gate");
    }
}
