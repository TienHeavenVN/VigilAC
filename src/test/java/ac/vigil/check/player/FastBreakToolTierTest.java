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
 * Regression tests for the tool-tier remediation: known tools get the full
 * vanilla budget, unknown tools suppress the timing verdict entirely, and
 * the FastBreak guarantee ("never flags a legal dig") is preserved.
 */
class FastBreakToolTierTest {

    private static final UUID PLAYER = UUID.randomUUID();

    private FastBreakCheck check;
    private PlayerData data;
    private List<Double> flagged;

    @BeforeEach
    void setUp() {
        check = new FastBreakCheck(3, 1.0D, 6.0D);
        data = new PlayerData(PLAYER);
        flagged = new ArrayList<>();
    }

    private ViolationCallback sink() {
        return (c, vl, debug) -> flagged.add(vl);
    }

    private BreakContext mine(String block, float hardness, boolean toolEffective, Double tierSpeed,
                              int eff, int haste, long timestamp) {
        BreakContext.Builder builder = BreakContext.builder()
                .player(PLAYER)
                .blockType(block)
                .block(0, 64, 0)
                .hardness(hardness)
                .toolEffective(toolEffective).canHarvest(toolEffective)
                .efficiency(eff)
                .hasteLevel(haste)
                .timestamp(timestamp);
        if (tierSpeed == null) {
            builder.toolSpeedUnknown();
        } else {
            builder.toolSpeed(tierSpeed);
        }
        return builder.build();
    }

    @Test
    void ironPickaxeOnStoneAtVanillaRateNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 12; i++) {
            now += 400L; // 375 ms budget; 400 ms is legal
            check.evaluate(mine("STONE", 1.5F, true, 6.0D, 0, 0, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "iron-on-stone at 400ms intervals is exactly the vanilla rate");
    }

    @Test
    void woodenPickaxeAtIronSpeedFlagsImpossibleStreak() {
        long now = 1_000_000L;
        for (int i = 0; i < 6; i++) {
            now += 400L; // budget is 1125 ms at speed 2.0; 400ms intervals are impossible
            check.evaluate(mine("STONE", 1.5F, true, 2.0D, 0, 0, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "wooden-pick speed with stone throughput is impossible");
    }

    @Test
    void unknownToolTierSuppressesEvenImpossibleLookingIntervals() {
        long now = 1_000_000L;
        for (int i = 0; i < 12; i++) {
            now += 250L; // would be impossible for EVERY known tool on stone
            check.evaluate(mine("STONE", 1.5F, true, null, 0, 0, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "unknown tool tier: no timing verdict, ever");
        assertEquals(0, data.blockActionTracker().getImpossibleBreakStreak(),
                "suppressed verdicts must not even accumulate the streak");
    }

    @Test
    void unknownToolDigLifecycleSuppressesPenalty() {
        long now = 1_000_000L;
        for (int i = 0; i < 30; i++) {
            now += 100L;
            BreakContext ctx = BreakContext.builder()
                    .player(PLAYER)
                    .blockType("OBSIDIAN")
                    .block(0, 64, 0)
                    .hardness(50.0F)
                    .toolEffective(true).canHarvest(true)
                    .efficiency(5)
                    .toolSpeedUnknown()
                    .digDurationMillis(200L) // absurdly fast; still no verdict
                    .timestamp(now)
                    .build();
            check.evaluate(ctx, data, sink());
        }
        assertTrue(flagged.isEmpty(), "unknown tool: digging-lifecycle path must not judge speed");
    }

    @Test
    void netheriteEfficiency5OnObsidianSlowerThanBudgetNeverFlags() {
        // obsidian eff5 netherite budget ~2143 ms; digs every 2.3 s are legal.
        long now = 1_000_000L;
        for (int i = 0; i < 4; i++) {
            now += 2_300L;
            BreakContext ctx = BreakContext.builder()
                    .player(PLAYER)
                    .blockType("OBSIDIAN")
                    .block(0, 64, 0)
                    .hardness(50.0F)
                    .toolEffective(true).canHarvest(true)
                    .efficiency(5)
                    .toolSpeed(9.0D)
                    .digDurationMillis(2_200L)
                    .timestamp(now)
                    .build();
            check.evaluate(ctx, data, sink());
        }
        assertTrue(flagged.isEmpty(), "a legal netherite+efficiency5 obsidian dig must never flag");
    }

    @Test
    void netheriteEfficiency5OnObsidianTooFastAccumulatesPenalty() {
        // budget ~2143 ms; constant 1.4 s digs: ~640 ms deficit per dig;
        // three digs cross the 1500 ms grace bucket.
        long now = 1_000_000L;
        for (int i = 0; i < 4; i++) {
            now += 1_450L;
            BreakContext ctx = BreakContext.builder()
                    .player(PLAYER)
                    .blockType("OBSIDIAN")
                    .block(0, 64, 0)
                    .hardness(50.0F)
                    .toolEffective(true).canHarvest(true)
                    .efficiency(5)
                    .toolSpeed(9.0D)
                    .digDurationMillis(1_400L)
                    .timestamp(now)
                    .build();
            check.evaluate(ctx, data, sink());
        }
        assertFalse(flagged.isEmpty(), "sustained under-budget netherite obsidian digs must convict");
    }

    @Test
    void wrongToolNeedsNoToolSpeed() {
        // obsidian mined bare-handed (hand = unknown tier): wrong-tool path is exact regardless.
        long now = 1_000_000L;
        for (int i = 0; i < 5; i++) {
            now += 200L;
            check.evaluate(mine("OBSIDIAN", 50.0F, false, null, 0, 0, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "wrong-tool path is always decidable");
    }
}
