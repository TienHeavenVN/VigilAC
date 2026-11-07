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

class FastBreakCheckTest {

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

    private BreakContext brk(float hardness, boolean correctTool, int efficiency, int haste, long timestamp) {
        return BreakContext.builder()
                .player(PLAYER)
                .blockType("OBSIDIAN")
                .block(0, 64, 0)
                .hardness(hardness)
                .correctTool(correctTool)
                .efficiency(efficiency)
                .hasteLevel(haste)
                .timestamp(timestamp)
                .build();
    }

    @Test
    void obsidianBrokenInhumanSpeedFlags() {
        long now = 1_000_000L;
        // Obsidian, no pickaxe: vanilla lower bound is 250 s. Cheat client breaks it in 200 ms.
        for (int i = 0; i < 5; i++) {
            now += 200L;
            check.evaluate(brk(50.0F, false, 0, 0, now), data, sink());
        }
        assertFalse(flagged.isEmpty(), "obsidian faster than vanilla physics must flag");
    }

    @Test
    void endgameStoneMiningNeverFlags() {
        long now = 1_000_000L;
        // Efficiency V diamond pickaxe + Haste II on stone: minimum ~33 ms; 100 ms intervals are legal.
        for (int i = 0; i < 12; i++) {
            now += 100L;
            check.evaluate(brk(1.15F, true, 5, 2, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "efficiency V + Haste II stone at 10 blocks/s is legal");
    }

    @Test
    void oneImpossibleBreakDoesNotFlag() {
        long now = 1_000_000L;
        check.evaluate(brk(50.0F, false, 0, 0, now), data, sink());
        assertTrue(flagged.isEmpty(), "a single impossible-looking break is never punished");
    }

    @Test
    void instantMaterialNeverFlags() {
        long now = 1_000_000L;
        for (int i = 0; i < 10; i++) {
            now += 30L;
            check.evaluate(brk(0.0F, true, 0, 0, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "instant-break materials carry no timing signal");
    }

    @Test
    void unbreakableMaterialCompletingFlags() {
        long now = 1_000_000L;
        check.evaluate(brk(-1.0F, false, 0, 0, now), data, sink()); // bedrock-ish
        assertFalse(flagged.isEmpty(), "completing unbreakable material is unconditionally impossible");
    }

    @Test
    void efLessOakWoodToolsKeepLegalBound() {
        long now = 1_000_000L;
        // Wooden pickaxe (eff 0) on stone: minimum ~1.73 s; breaking every 1.8 s is legal.
        for (int i = 0; i < 6; i++) {
            now += 1_800L;
            check.evaluate(brk(1.15F, true, 0, 0, now), data, sink());
        }
        assertTrue(flagged.isEmpty(), "wooden tools at their vanilla rate must not flag");
    }
}
