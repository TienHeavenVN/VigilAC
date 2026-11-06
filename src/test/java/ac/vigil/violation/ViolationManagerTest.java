package ac.vigil.violation;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.config.CheckSettings;
import ac.vigil.data.PlayerData;

class ViolationManagerTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private ViolationManager manager;
    private PlayerData data;
    private List<Verdict> verdicts;

    @BeforeEach
    void setUp() {
        Function<String, CheckSettings> settings = name -> switch (name) {
            case "speed" -> new CheckSettings("speed", true, 1.0D, 2.0D, 3.0D, 4.0D);
            case "reach" -> new CheckSettings("reach", false, 1.0D, 2.0D, 3.0D, 4.0D);
            default -> null;
        };
        manager = new ViolationManager(settings);
        verdicts = new ArrayList<>();
        manager.addListener((violation, verdict) -> verdicts.add(verdict));
        data = new PlayerData(PLAYER);
    }

    @Test
    void verdictEscalatesThroughThresholds() {
        assertEquals(Verdict.NONE, manager.record(PLAYER, "speed", 0.6D, "t", data));   // vl 0.6
        assertEquals(Verdict.ALERT, manager.record(PLAYER, "speed", 0.6D, "t", data));  // vl 1.2
        assertEquals(Verdict.ALERT, manager.record(PLAYER, "speed", 0.6D, "t", data));  // vl 1.8
        assertEquals(Verdict.WARN, manager.record(PLAYER, "speed", 0.6D, "t", data));   // vl 2.4
        assertEquals(Verdict.KICK, manager.record(PLAYER, "speed", 0.6D, "t", data));   // vl 3.0
    }

    @Test
    void vlIsCappedAtMaxVl() {
        for (int i = 0; i < 20; i++) {
            manager.record(PLAYER, "speed", 1.0D, "t", data);
        }
        assertEquals(4.0D, data.getViolationLevel("speed"), 1e-9);
    }

    @Test
    void disabledCheckProducesNothing() {
        assertEquals(Verdict.NONE, manager.record(PLAYER, "reach", 5.0D, "t", data));
        assertEquals(0.0D, data.getViolationLevel("reach"), 1e-9);
        assertTrue(verdicts.isEmpty(), "disabled checks must not notify listeners");
    }

    @Test
    void unknownCheckProducesNothing() {
        assertEquals(Verdict.NONE, manager.record(PLAYER, "nosuchcheck", 5.0D, "t", data));
        assertTrue(verdicts.isEmpty());
    }

    @Test
    void nonPositiveAndNaNDeltasAreIgnored() {
        assertEquals(Verdict.NONE, manager.record(PLAYER, "speed", 0.0D, "t", data));
        assertEquals(Verdict.NONE, manager.record(PLAYER, "speed", Double.NaN, "t", data));
        assertEquals(Verdict.NONE, manager.record(PLAYER, "speed", Double.POSITIVE_INFINITY, "t", data));
        assertEquals(0.0D, data.getViolationLevel("speed"), 1e-9);
        assertTrue(verdicts.isEmpty());
    }

    @Test
    void decayReducesVlWhenDelayHasElapsed() {
        manager.record(PLAYER, "speed", 2.0D, "t", data);
        assertEquals(2.0D, data.getViolationLevel("speed"), 1e-9);
        // Zero delay: decays immediately.
        assertTrue(manager.decay(data, 0.5D, 0L));
        assertEquals(1.5D, data.getViolationLevel("speed"), 1e-9);
    }

    @Test
    void decayRespectsDelayWindow() {
        manager.record(PLAYER, "speed", 2.0D, "t", data);
        // Huge delay: no decay yet.
        assertFalse(manager.decay(data, 0.5D, 60_000L));
        assertEquals(2.0D, data.getViolationLevel("speed"), 1e-9);
    }

    @Test
    void listenersReceiveEveryRecordedVerdict() {
        manager.record(PLAYER, "speed", 0.5D, "a", data);
        manager.record(PLAYER, "speed", 0.5D, "b", data);
        assertEquals(2, verdicts.size());
    }
}
