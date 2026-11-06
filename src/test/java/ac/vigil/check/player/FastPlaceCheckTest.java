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

class FastPlaceCheckTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FastPlaceCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new FastPlaceCheck(7.0D, 2_000L, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private PlaceContext place(long timestamp) {
        return PlaceContext.builder()
                .player(PLAYER)
                .blockType("OAK_PLANKS")
                .block(0, 64, 0)
                .feet(0.0D, 64.0D, 0.0D)
                .yaw(0.0F)
                .pitch(30.0F)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void sustainedMachineRateFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 20; i++) { // 100 ms apart: 10/s sustained
            now += 100L;
            check.evaluate(place(now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "10 placements per second must exceed the 7/s cap");
    }

    @Test
    void vanillaPaceNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 12; i++) { // 400 ms apart: 2.5/s
            now += 400L;
            check.evaluate(place(now), data, sink());
        }
        assertTrue(flagged.isEmpty());
    }

    @Test
    void briefBurstBelowWindowDoesNotFlag() {
        long now = 1_000_000L;
        // Six quick placements (0.6s apart) then a pause: never 7/s over the full window.
        for (int i = 0; i < 6; i++) {
            now += 150L;
            check.evaluate(place(now), data, sink());
        }
        now += 2_000L; // window drains
        check.evaluate(place(now), data, sink());
        assertTrue(flagged.isEmpty(), "short bursts that never sustain the window rate must not flag");
    }
}
