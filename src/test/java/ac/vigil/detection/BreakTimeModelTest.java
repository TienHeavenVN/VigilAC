package ac.vigil.detection;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Formula contract for the pure vanilla break-time model. Numbers below are
 * derived by hand from the vanilla hardness scale; they also pin the two
 * regression cases the audit under-tested (iron-on-stone, netherite-on-obsidian).
 */
class BreakTimeModelTest {

    @Test
    void ironPickaxeOnStoneIs375ms() {
        // stone hardness 1.5, iron speed 6: 1.5 * 1.5 / 6.0 = 0.375 s
        double ms = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        assertEquals(375.0D, ms, 0.5D);
    }

    @Test
    void netheritePickaxeEfficiency5OnObsidianIsAbout2143ms() {
        // obsidian hardness 50, speed = 9 + (25 + 1) = 35:
        // 50 * 1.5 / 35 = 2.142857 s
        double ms = BreakTimeModel.expectedMillis(50.0D, true, true, true, true, 9.0D, 5, 0, 0, false, false, true);
        assertEquals(2_142.86D, ms, 1.0D);
    }

    @Test
    void woodenPickaxeOnStoneIs1125ms() {
        // 1.5 * 1.5 / 2.0 = 1.125 s = 1125 ms
        double ms = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 2.0D, 0, 0, 0, false, false, true);
        assertEquals(1_125.0D, ms, 0.5D);
    }

    @Test
    void goldenPickaxeHighestBaseSpeed() {
        // irrelevant-block test: golden base 12
        double ms = BreakTimeModel.expectedMillis(1.0D, true, true, true, true, 12.0D, 0, 0, 0, false, false, true);
        assertEquals(125.0D, ms, 0.5D); // 1.5 / 12 * 1000
    }

    @Test
    void hasteBoostsDigSpeed() {
        double plain = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        double haste2 = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 2, 0, false, false, true);
        assertEquals(plain / 1.4D, haste2, 0.5D);
    }

    @Test
    void miningFatigueSlowsByThirtyPercentPerLevel() {
        double plain = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        double fatigue1 = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 1, false, false, true);
        assertEquals(plain / 0.3D, fatigue1, 1.0D);
    }

    @Test
    void underwaterWithoutAquaAffinityIsFiveTimesSlower() {
        double dry = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        double wet = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, true, false, true);
        assertEquals(dry / 0.2D, wet, 0.5D);
        double wetAqua = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, true, true, true);
        assertEquals(dry, wetAqua, 0.5D);
    }

    @Test
    void offGroundIsFiveTimesSlower() {
        double grounded = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true);
        double airborne = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 0, 0, 0, false, false, false);
        assertEquals(grounded / 0.2D, airborne, 0.5D);
    }

    @Test
    void wrongToolUses5xDivisorAndHandSpeed() {
        // obsidian with no pick: 50 * 5.0 / 1.0 = 250 s
        double ms = BreakTimeModel.expectedMillis(50.0D, false, false, true, true, 2.0D, 0, 0, 0, false, false, true);
        assertEquals(250_000.0D, ms, 1.0D);
    }

    @Test
    void unknownCorrectToolSuppressesTheVerdict() {
        assertTrue(Double.isNaN(BreakTimeModel.expectedMillis(1.5D, true, true, true, false, 1.0D, 0, 0, 0, false, false, true)));
        assertTrue(Double.isNaN(BreakTimeModel.minimumMillis(1.5D, true, true, true, false, 1.0D, 0, 0)));
    }

    @Test
    void unknownWrongToolNeverSuppresses() {
        assertFalse(Double.isNaN(BreakTimeModel.expectedMillis(1.5D, false, false, true, false, 1.0D, 0, 0, 0, false, false, true)));
        assertFalse(Double.isNaN(BreakTimeModel.minimumMillis(1.5D, false, false, true, false, 1.0D, 0, 0)));
    }

    @Test
    void instantMaterialsNeedZeroTime() {
        assertEquals(0.0D, BreakTimeModel.expectedMillis(0.0D, true, true, true, true, 6.0D, 0, 0, 0, false, false, true));
        assertEquals(0.0D, BreakTimeModel.minimumMillis(0.0D, true, true, true, true, 6.0D, 0, 0));
    }

    @Test
    void toolTierMappingIsConservative() {
        assertEquals(BreakTimeModel.ToolTier.NETHERITE, BreakTimeModel.ToolTier.forMaterial("NETHERITE_PICKAXE"));
        assertEquals(BreakTimeModel.ToolTier.IRON, BreakTimeModel.ToolTier.forMaterial("IRON_SHOVEL"));
        assertEquals(BreakTimeModel.ToolTier.DIAMOND, BreakTimeModel.ToolTier.forMaterial("DIAMOND_AXE"));
        assertEquals(BreakTimeModel.ToolTier.STONE, BreakTimeModel.ToolTier.forMaterial("STONE_PICKAXE"));
        assertEquals(BreakTimeModel.ToolTier.WOODEN, BreakTimeModel.ToolTier.forMaterial("WOODEN_AXE"));
        assertEquals(BreakTimeModel.ToolTier.GOLDEN, BreakTimeModel.ToolTier.forMaterial("GOLDEN_PICKAXE"));
        // Not vanilla-mapped in the pinned 1.21.8 API: must NOT be guessed
        assertNull(BreakTimeModel.ToolTier.forMaterial("COPPER_PICKAXE"));
        assertNull(BreakTimeModel.ToolTier.forMaterial("STICK"));
        assertNull(BreakTimeModel.ToolTier.forMaterial(null));
    }

    @Test
    void efficiencyMathMatchesVanilla() {
        // eff III on stone with iron pick: speed = 6 + (9 + 1) = 16
        double ms = BreakTimeModel.expectedMillis(1.5D, true, true, true, true, 6.0D, 3, 0, 0, false, false, true);
        assertEquals(1.5D * 1.5D / 16.0D * 1_000.0D, ms, 0.5D);
    }
}
