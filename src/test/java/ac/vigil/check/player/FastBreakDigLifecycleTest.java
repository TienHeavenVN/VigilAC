package ac.vigil.check.player;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ac.vigil.check.ViolationCallback;
import ac.vigil.data.PlayerData;
import ac.vigil.detection.BreakContext;

/**
 * Path A (digging lifecycle + penalty bucket) of the v2 fast-break check.
 * packetevents is not installed in CI, so the dig fields are injected directly
 * through {@link BreakContext.Builder} — this exercises the exact math the
 * packet path feeds in production.
 */
class FastBreakDigLifecycleTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FastBreakCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new FastBreakCheck(3, 1.0D, 6.0D, 1_500.0D, 1.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private BreakContext dig(long digMillis, long timestamp) {
        return BreakContext.builder()
                .player(PLAYER)
                .blockType("OBSIDIAN")
                .block(0, 64, 0)
                .hardness(50.0F) // vanilla lower bound: 250s (wrong tool) — cheat digs it in ms
                .correctTool(false)
                .efficiency(0)
                .hasteLevel(0)
                .digDurationMillis(digMillis)
                .timestamp(timestamp)
                .build();
    }

    private BreakContext marginalDig(long durationMillis, long timestamp) {
        // STONE, correct tool, no Efficiency: expected 2.25 s. A dig that is
        // 150 ms faster than expectation — marginal, almost legal.
        return BreakContext.builder()
                .player(PLAYER)
                .blockType("STONE")
                .block(0, 64, 0)
                .hardness(1.5F)
                .correctTool(true)
                .efficiency(0)
                .hasteLevel(0)
                .digDurationMillis(durationMillis)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void singleMarginalFastDigNeverFlags() {
        long now = 1_000_000L;
        check.evaluate(marginalDig(2_100L, now), data, sink());
        assertTrue(flagged.isEmpty(), "one marginally-fast dig credits the bucket, it never flags");
    }

    @Test
    void burstOfMarginalFastDigsFlagsViaPenaltyBucket() {
        long now = 1_000_000L;
        // 30 barely-impossible digs in a burst (100 ms apart) — a speedhack
        // signature. Each credits ~150 ms; the bucket crosses the 1500 ms grace.
        for (int i = 0; i < 30; i++) {
            now += 100L;
            check.evaluate(marginalDig(2_100L, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "a burst of impossible digs must trip the penalty bucket");
    }

    @Test
    void spacedMarginalFastDigsDoNotAccumulateForever() {
        long now = 1_000_000L;
        // Same 150 ms deficit but spaced 3 s apart: the bucket decays faster
        // than it fills — borderline-legal, must not flag.
        for (int i = 0; i < 12; i++) {
            now += 3_000L;
            check.evaluate(marginalDig(2_100L, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "spaced borderline digs decay below the grace threshold");
    }

    @Test
    void definitivelyImpossibleDigFlagsImmediately() {
        long now = 1_000_000L;
        // OBSIDIAN wrong-tool: expected ~250 s. A 150 ms dig is impossible by
        // any reading of vanilla physics.
        check.evaluate(dig(150L, now), data, sink());
        assertFalse(flagged.isEmpty(), "a physically impossible dig may flag on confidence alone");
    }

    @Test
    void legalDigDurationNeverFlags() {
        long now = 1_000_000L;
        // Expected for OBSIDIAN wrong-tool, haste 0: 250 s. Dig for 250.5 s.
        for (int i = 0; i < 3; i++) {
            now += 300_000L;
            check.evaluate(dig(250_500L, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "legal digging duration must never flag");
    }

    @Test
    void inconclusiveDigsNeverFlag() {
        long now = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            now += 300L;
            BreakContext ctx = BreakContext.builder()
                    .player(PLAYER)
                    .blockType("OBSIDIAN")
                    .block(0, 64, 0)
                    .hardness(50.0F)
                    .correctTool(false)
                    .digDurationMillis(100L)
                    .digInconclusive(true) // tool swapped mid-dig
                    .timestamp(now)
                    .build();
            check.evaluate(ctx, data, sink());
        }
        assertTrue(flagged.isEmpty(), "tool-swapped digs are inconclusive, never flagged");
    }

    @Test
    void fatigueBlocksFastBreakLegitimacy() {
        long now = 1_000_000L;
        // Correct tool, Efficiency V (speed 27), Haste II, but Mining Fatigue V
        // (0.3^5 ≈ 0.00243): expected ≈ hardness*1.5/27 * 1/0.00243 ≈ huge.
        // A 50 ms dig is impossible here; repeated digs must flag.
        for (int i = 0; i < 4; i++) {
            now += 400L;
            BreakContext ctx = BreakContext.builder()
                    .player(PLAYER)
                    .blockType("STONE")
                    .block(0, 64, 0)
                    .hardness(1.5F)
                    .correctTool(true)
                    .efficiency(5)
                    .hasteLevel(2)
                    .miningFatigueLevel(5)
                    .digDurationMillis(50L)
                    .timestamp(now)
                    .build();
            check.evaluate(ctx, data, sink());
        }
        assertFalse(flagged.isEmpty(), "fatigue-slowed digging cannot legitimately be fast");
    }

    @Test
    void waterWithoutAquaAffinitySlowsExpectedBreak() {
        long now = 1_000_000L;
        // Same stone dig, underwater without aqua affinity (÷5): expected ≈ 1.5*1.5/27*5 s ≈ 417 ms.
        BreakContext fastUnderwater = BreakContext.builder()
                .player(PLAYER)
                .blockType("STONE")
                .block(0, 64, 0)
                .hardness(1.5F)
                .correctTool(true)
                .efficiency(5)
                .inWater(true)
                .digDurationMillis(100L)
                .timestamp(now)
                .build();
        assertTrue(fastUnderwater.expectedBreakMillis() > 100.0D,
                "underwater digging is 5x slower without aqua affinity");

        BreakContext fastUnderwaterAffinity = BreakContext.builder()
                .player(PLAYER)
                .blockType("STONE")
                .block(0, 64, 0)
                .hardness(1.5F)
                .correctTool(true)
                .efficiency(5)
                .inWater(true)
                .aquaAffinity(true)
                .digDurationMillis(100L)
                .timestamp(now)
                .build();
        assertEquals(fastUnderwater.expectedBreakMillis() / 5.0D, fastUnderwaterAffinity.expectedBreakMillis(), 1.0D);
    }
}
