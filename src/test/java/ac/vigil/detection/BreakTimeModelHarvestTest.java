package ac.vigil.detection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Regression tests for the wrong-tool/harvestability split (independent-review
 * remediation). Expected values below are derived from the VANILLA formula and
 * vanilla block behaviours (hardness, drop requirements), NOT from this
 * implementation's helper layout — they would hold for any correct
 * implementation of the vanilla break-time rule:
 *
 * <pre>
 *   ticks = hardness × (block drops without any tool || tool satisfies the drop
 *          requirement ? 30 : 100) / speed
 *   speed = (held tool's family is effective for the block ? tool speed : 1)
 *           + (effective && Efficiency &gt; 0 ? eff² + 1 : 0),  × haste/fatigue/water/air
 * </pre>
 *
 * Vanilla reference points used below: iron pickaxe on stone 0.375 s;
 * wooden pickaxe on stone 1.125 s; iron pickaxe on obsidian (cannot harvest)
 * 41.667 s; wooden pickaxe on iron ore (cannot harvest) 7.5 s.
 */
class BreakTimeModelHarvestTest {

    // ------------------------------------------------- wrong-tool speed applies? no. Divisor? it depends.

    @Test
    void wrongFamilyToolOnDirtHarvestableAtHandSpeed() {
        // DIRT (hardness 0.5) drops without any tool. An iron pickaxe is not
        // effective for dirt (shovel family), so speed is 1.0 — but the drop
        // requirement does not exist, so the divisor is 30 ticks, NOT 100.
        // Vanilla: 0.5 × 30 / 1.0 = 15 ticks = 750 ms. The pre-remediation
        // model produced 2500 ms here (overestimated ×3.33) and flagged
        // players mining dirt with a pickaxe at 2 blocks/s as "impossible".
        double ms = BreakTimeModel.expectedMillis(0.5D, false, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        assertEquals(750.0D, ms, 1.0D);
    }

    @Test
    void handOnDirtHarvestableAtHandSpeed() {
        double ms = BreakTimeModel.expectedMillis(0.5D, false, true, true, true, 1.0D, 0, 0, 0, false, false, true);
        assertEquals(750.0D, ms, 1.0D);
    }

    @Test
    void bareHandOnGlowstoneIsHarvestable() {
        // GLOWSTONE drops dust with any tool (or hand): divisor 30 ticks.
        double ms = BreakTimeModel.expectedMillis(0.3D, false, true, true, true, 1.0D, 0, 0, 0, false, false, true);
        assertEquals(450.0D, ms, 1.0D);
    }

    @Test
    void axeOnStoneCannotHarvestAndRunsAtHandSpeed() {
        // STONE requires a pickaxe for the drop; an axe is not effective for
        // stone either. speed 1.0, divisor 100 ticks → 150 ticks = 7.5 s.
        double ms = BreakTimeModel.expectedMillis(1.5D, false, false, true, true, 8.0D, 0, 0, 0, false, false, true);
        assertEquals(7_500.0D, ms, 1.0D);
    }

    // ------------------------------------------------- tool family effective, tier insufficient for the drop

    @Test
    void woodenPickaxeOnIronOreCannotHarvestButSpeedApplies() {
        // Vanilla: iron ore drops only with stone pickaxe+. A wooden pickaxe
        // is effective (speed 2.0) but cannot harvest → divisor 100 ticks.
        // 3.0 × 100 / 2.0 = 150 ticks = 7.5 s.
        double ms = BreakTimeModel.expectedMillis(3.0D, true, false, true, true, 2.0D, 0, 0, 0, false, false, true);
        assertEquals(7_500.0D, ms, 1.0D);
    }

    @Test
    void stonePickaxeOnIronOreHarvests() {
        // 3.0 × 30 / 4.0 = 22.5 ticks = 1125 ms
        double ms = BreakTimeModel.expectedMillis(3.0D, true, true, true, true, 4.0D, 0, 0, 0, false, false, true);
        assertEquals(1_125.0D, ms, 1.0D);
    }

    @Test
    void goldenPickaxeCannotHarvestGoldOreAtAnySpeed() {
        // Gold pickaxe is tier 0 (wood equivalent) for harvestability:
        // gold ore needs iron pickaxe+. It digs at speed 12 but gets NO drops.
        // 3.0 × 100 / 12.0 = 25 ticks = 1250 ms.
        double ms = BreakTimeModel.expectedMillis(3.0D, true, false, true, true, 12.0D, 0, 0, 0, false, false, true);
        assertEquals(1_250.0D, ms, 1.0D);
    }

    @Test
    void ironPickaxeOnGoldOreHarvests() {
        // 3.0 × 30 / 6.0 = 15 ticks = 750 ms
        double ms = BreakTimeModel.expectedMillis(3.0D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        assertEquals(750.0D, ms, 1.0D);
    }

    @Test
    void woodenPickaxeOnObsidianDigsAt2SpeedWithoutDrops() {
        // Vanilla: obsidian needs a diamond pickaxe for the drop; a wooden
        // pickaxe still digs it at speed 2.0, 100-tick divisor.
        // 50 × 100 / 2.0 = 2500 ticks = 125 s.
        double ms = BreakTimeModel.expectedMillis(50.0D, true, false, true, true, 2.0D, 0, 0, 0, false, false, true);
        assertEquals(125_000.0D, ms, 1.0D);
    }

    @Test
    void ironPickaxeOnObsidianCannotHarvestButStillFast() {
        // 50 × 100 / 6.0 = 833.33 ticks ≈ 41.667 s
        double ms = BreakTimeModel.expectedMillis(50.0D, true, false, true, true, 6.0D, 0, 0, 0, false, false, true);
        assertEquals(41_666.67D, ms, 5.0D);
    }

    @Test
    void diamondPickaxeOnObsidianHarvests() {
        // 50 × 30 / 8.0 = 187.5 ticks = 9375 ms
        double ms = BreakTimeModel.expectedMillis(50.0D, true, true, true, true, 8.0D, 0, 0, 0, false, false, true);
        assertEquals(9_375.0D, ms, 1.0D);
    }

    @Test
    void efficiencyAppliesEvenWhenTheToolCannotHarvest() {
        // Wooden pickaxe + Efficiency II on obsidian: base 2.0 + (4 + 1) = 7.0;
        // it cannot harvest, so the divisor stays 100 ticks.
        // 50 × 100 / 7.0 = 714.29 ticks ≈ 35.714 s.
        double ms = BreakTimeModel.expectedMillis(50.0D, true, false, true, true, 2.0D, 2, 0, 0, false, false, true);
        assertEquals(35_714.29D, ms, 5.0D);
    }

    @Test
    void efficiencyDoesNotApplyToNonEffectiveTools() {
        // Vanilla gate: Efficiency applies only when the base speed is > 1
        // (i.e. the tool is effective for the block). Pickaxe on dirt: speed
        // stays 1.0 and Efficiency III is ignored: 0.5 × 30 / 1.0 = 750 ms.
        double ms = BreakTimeModel.expectedMillis(0.5D, false, true, true, true, 6.0D, 3, 0, 0, false, false, true);
        assertEquals(750.0D, ms, 1.0D);
    }

    @Test
    void hasteAppliesEvenToNonEffectiveTools() {
        // Haste is a player attribute, not a tool property: it multiplies
        // dig speed whatever the tool. 0.5 × 30 / (1.0 × 1.4) ≈ 10.71 ticks ≈ 535.7 ms.
        double ms = BreakTimeModel.expectedMillis(0.5D, false, true, true, true, 6.0D, 0, 2, 0, false, false, true);
        assertEquals(535.71D, ms, 1.0D);
    }

    @Test
    void stonePickaxeOnQuarryGravelShovelEffective() {
        // GRAVEL (0.6) is shovel-family: an iron shovel is effective, speed 6.0;
        // gravel drops without any tool. 0.6 × 30 / 6.0 = 3 ticks = 150 ms.
        double ms = BreakTimeModel.expectedMillis(0.6D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        assertEquals(150.0D, ms, 1.0D);
    }

    @Test
    void hasteFatigueWaterAirStackOnWrongToolPath() {
        // Wrong family on dirt with fatigue I: speed = 1.0 × 0.3 = 0.3;
        // 0.5 × 30 / 0.3 = 50 ticks = 2500 ms.
        double ms = BreakTimeModel.expectedMillis(0.5D, false, true, true, true, 6.0D, 0, 0, 1, false, false, true);
        assertEquals(2_500.0D, ms, 1.0D);
    }

    // ------------------------------------------------- minimum (hard floor) honours the harvest divisor

    @Test
    void minimumFloorIsVanillaBestCase() {
        // Netherite pickaxe, Efficiency V on obsidian: floor = 50 × 30 / 35 = 42.86 ticks ≈ 2143 ms
        double floor = BreakTimeModel.minimumMillis(50.0D, true, true, true, true, 9.0D, 5, 0);
        assertEquals(2_142.86D, floor, 1.0D);
        // Iron pickaxe on obsidian can never harvest: floor stays at 50 × 100 / 6 ≈ 41.7 s
        double ironFloor = BreakTimeModel.minimumMillis(50.0D, true, false, true, true, 6.0D, 0, 0);
        assertEquals(41_666.67D, ironFloor, 5.0D);
    }

    // ------------------------------------------------- unknown mechanics: never conviction evidence

    @Test
    void unknownBlockMechanicsProduceNoVerdict() {
        // A block whose name matches no vanilla family table (modded):
        // no timing verdict at all, even for a known tool — never an invented budget.
        assertTrue(Double.isNaN(BreakTimeModel.expectedMillis(4.0D, true, true, false, true, 6.0D, 0, 0, 0, false, false, true)));
        assertTrue(Double.isNaN(BreakTimeModel.minimumMillis(4.0D, true, true, false, true, 6.0D, 0, 0)));
        assertFalse(BreakTimeModel.isBlockRecognized("QUANTUM_MATRIX"));
        assertTrue(BreakTimeModel.isBlockRecognized("STONE"));
    }

    // ------------------------------------------------- classification contract (shared by listener and model)

    @Test
    void classificationContractsMatchVanillaRules() {
        assertTrue(BreakTimeModel.toolEffective("STONE", "IRON_PICKAXE"));
        assertFalse(BreakTimeModel.toolEffective("DIRT", "IRON_PICKAXE"));
        assertTrue(BreakTimeModel.toolEffective("DIRT", "IRON_SHOVEL"));
        assertTrue(BreakTimeModel.toolEffective("OAK_LOG", "IRON_AXE"));

        assertTrue(BreakTimeModel.canHarvest("STONE", "WOODEN_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("STONE", "GOLDEN_PICKAXE"));   // gold is tier 0 ≥ any-pickaxe requirement
        assertFalse(BreakTimeModel.canHarvest("STONE", "AIR"));              // stone needs a pickaxe
        assertTrue(BreakTimeModel.canHarvest("DIRT", "AIR"));                // dirt needs nothing
        assertFalse(BreakTimeModel.canHarvest("IRON_ORE", "WOODEN_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("IRON_ORE", "STONE_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("IRON_ORE", "GOLDEN_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("COPPER_ORE", "STONE_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("GOLD_ORE", "IRON_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("GOLD_ORE", "GOLDEN_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("NETHER_GOLD_ORE", "WOODEN_PICKAXE")); // nether gold ore drops by hand
        assertTrue(BreakTimeModel.canHarvest("DEEPSLATE_GOLD_ORE", "IRON_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("DEEPSLATE_GOLD_ORE", "STONE_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("LAPIS_ORE", "STONE_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("OBSIDIAN", "IRON_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("OBSIDIAN", "DIAMOND_PICKAXE"));
        assertTrue(BreakTimeModel.canHarvest("OBSIDIAN", "NETHERITE_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("OBSIDIAN", "COPPER_PICKAXE")); // unmapped tier: never guessed
        assertTrue(BreakTimeModel.canHarvest("ANCIENT_DEBRIS", "DIAMOND_PICKAXE"));
        assertFalse(BreakTimeModel.canHarvest("ANCIENT_DEBRIS", "IRON_PICKAXE"));

        assertEquals(BreakTimeModel.HarvestTier.DIAMOND, BreakTimeModel.requiredHarvest("CRYING_OBSIDIAN"));
        assertEquals(BreakTimeModel.HarvestTier.NONE, BreakTimeModel.requiredHarvest("GLOWSTONE"));
        assertEquals(BreakTimeModel.HarvestTier.NONE, BreakTimeModel.requiredHarvest("SANDSTONE"));
        assertEquals(BreakTimeModel.HarvestTier.STONE, BreakTimeModel.requiredHarvest("DEEPSLATE_COPPER_ORE"));
        assertEquals(BreakTimeModel.HarvestTier.STONE, BreakTimeModel.requiredHarvest("IRON_BLOCK"));
        assertEquals(BreakTimeModel.HarvestTier.STONE, BreakTimeModel.requiredHarvest("LAPIS_BLOCK"));
        assertEquals(BreakTimeModel.HarvestTier.WOOD, BreakTimeModel.requiredHarvest("REDSTONE_BLOCK"));
        assertEquals(BreakTimeModel.HarvestTier.WOOD, BreakTimeModel.requiredHarvest("QUARTZ_BLOCK"));
        assertEquals(BreakTimeModel.HarvestTier.IRON, BreakTimeModel.requiredHarvest("DEEPSLATE_GOLD_ORE"));
        assertEquals(BreakTimeModel.HarvestTier.DIAMOND, BreakTimeModel.requiredHarvest("NETHERITE_BLOCK"));
    }
}
