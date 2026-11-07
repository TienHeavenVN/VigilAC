package ac.vigil.detection;

/**
 * Pure-Java vanilla break-time model (no Bukkit). Central place every number
 * lives, shared by {@link BreakContext}'s budgets, the listener's block/tool
 * classification and the unit tests.
 *
 * <h2>Vanilla formula (unchanged across the supported 1.21–1.21.11 / 26.x line)</h2>
 * <ul>
 *   <li>{@code ticks = hardness × (harvestable ? 30 : 100) / speed} — i.e. a
 *       correct (harvesting) dig divides by 30 ticks, a dig that cannot
 *       harvest divides by 100.</li>
 *   <li>{@code speed} starts at the held tool's base dig speed <b>only when the
 *       tool's family is effective for the block</b> (pickaxe-family block vs
 *       pickaxe, axe vs logs/wood, shovel vs dirt/sand/gravel, hoe vs
 *       leaves/hay-family); otherwise it is 1.0. The tier sufficiency for the
 *       block's drop requirement does <em>not</em> gate the speed — a wooden
 *       pickaxe digs obsidian at speed 2.0; it simply gets no drops
 *       (100-tick divisor).</li>
 *   <li>Efficiency (cap V) adds {@code eff² + 1} to the speed — but only when
 *       the tool is effective for the block (vanilla gate: base speed &gt; 1).</li>
 *   <li>Haste multiplies speed by {@code 1 + 0.2 × level} (cap IV in this model).
 *       Mining Fatigue multiplies by {@code 0.3 ^ level} (cap IV).</li>
 *   <li>Underwater without Aqua Affinity multiplies by 0.2. Off-ground by 0.2.</li>
 *   <li>{@code hardness ≤ 0} ⇒ 0 ms (instant, universal — applies even to
 *       unrecognized blocks).</li>
 * </ul>
 *
 * <h2>Harvestability vs. effectiveness vs. recognition — the two axes, separated</h2>
 * Vanilla uses three independent facts and this model does too:
 * <ol>
 *   <li><b>toolEffective</b> — the tool family is effective for the block. Gates
 *       the tool base speed and Efficiency.</li>
 *   <li><b>canHarvest</b> — the block's drop requires a tool and the held tool
 *       satisfies it. Gates the 30/100-tick divisor only. Blocks that drop
 *       without any tool (dirt, sand, logs, wool, glowstone, …) are always
 *       harvestable; blocks whose drop requires a pickaxe need a pickaxe of
 *       sufficient tier ({@link #requiredHarvest}).</li>
 *   <li><b>miningModelKnown</b> — the block is recognized by this model's
 *       family tables. Unknown blocks (modded mechanics) return
 *       {@link Double#NaN} for both budgets: <b>no timing verdict, ever</b> —
 *       unknown mechanics are never converted into conviction evidence.</li>
 * </ol>
 *
 * <h2>Honesty rule: no invented tool speeds</h2>
 * When the held item cannot be mapped to a vanilla tool family (hand on a
 * tool-required block is fine — speed 1.0 is vanilla; an unmapped <em>tool</em>
 * like a modded or copper pickaxe on an effective block), the timing
 * <em>verdict</em> is suppressed: {@code NaN} budgets mean "cannot be judged",
 * never "fast". Non-effective unmappable items fall back to hand speed and
 * are still decidable (vanilla does exactly that).
 *
 * <p>Copper-tier tools shipped in recent snapshots: the plugin is compiled
 * against 1.21.8 where their speed is not part of the pinned API's contract,
 * so they intentionally resolve to UNKNOWN — see the compatibility matrix.</p>
 *
 * <h2>Recognition of block mechanics</h2>
 * A block is "recognized" when it matches the family tables below. The
 * required-pickaxe-tier table ({@link #requiredHarvest}) reflects the vanilla
 * drop requirements for 1.21 through 26.x (unchanged in that range): stone-family
 * at any pickaxe, iron/lapis/copper ore at stone pickaxe, gold/redstone/
 * diamond/emerald ore + gold/diamond/emerald blocks at iron pickaxe, and
 * obsidian/ancient debris/netherite blocks/respawn anchor/enchanting table at
 * diamond pickaxe. Pickaxe-effective blocks with no tool requirement
 * (sandstone, glowstone, netherrack, magma block, sea lantern, nether gold
 * ore) are mapped explicitly. <b>Any unrecognized pickaxe-family block is
 * treated as requiring a diamond pickaxe</b> (quietest possible honest choice);
 * unrecognized non-pickaxe blocks carry no drop requirement. When in doubt the
 * model errs toward longer budgets (fewer flags), never toward shorter ones.
 */
public final class BreakTimeModel {

    /** Hard vanilla efficiency cap (Efficiency V). */
    public static final int MAX_EFFICIENCY = 5;
    /** Hard haste cap used for the model (Haste IV supplied here as IV). */
    public static final int MAX_HASTE = 4;
    /** Mining fatigue cap for the model. */
    public static final int MAX_FATIGUE = 4;

    /** Vanilla tool base speeds. */
    public enum ToolTier {
        WOODEN(2.0D, "WOODEN_"),
        STONE(4.0D, "STONE_"),
        IRON(6.0D, "IRON_"),
        GOLDEN(12.0D, "GOLDEN_", "GOLD_"),
        DIAMOND(8.0D, "DIAMOND_"),
        NETHERITE(9.0D, "NETHERITE_");

        private final double speed;
        private final String[] markers;

        ToolTier(double speed, String... markers) {
            this.speed = speed;
            this.markers = markers;
        }

        public double speed() {
            return speed;
        }

        /** Tool tier for a material name, or null when it is not a known vanilla tool family. */
        public static ToolTier forMaterial(String materialName) {
            if (materialName == null) {
                return null;
            }
            for (ToolTier tier : values()) {
                for (String marker : tier.markers) {
                    if (materialName.contains(marker)) {
                        return tier;
                    }
                }
            }
            return null;
        }
    }

    /** Tool families relevant to melee/mining effectiveness. */
    public enum ToolFamily {
        PICKAXE,
        AXE,
        SHOVEL,
        HOE,
        /** Hand, sword, or anything unmappable: not effective for any mining family. */
        OTHER
    }

    /** Pickaxe tier required to harvest a block's drop (vanilla 1.16–1.21.x / 26.x). */
    public enum HarvestTier {
        /** Drops without any tool. */
        NONE,
        WOOD,
        STONE,
        IRON,
        DIAMOND
    }

    /**
     * Best-effort tool base speed for a held-item material name,
     * or null when no reliable value exists (caller suppresses the timing verdict).
     */
    public static Double toolSpeedFor(String materialName) {
        ToolTier tier = ToolTier.forMaterial(materialName);
        return tier == null ? null : tier.speed();
    }

    /** Tool family for a held-item material name; {@link ToolFamily#OTHER} for hand/sword/modded. */
    public static ToolFamily toolFamily(String toolName) {
        String t = toolName == null ? "" : toolName;
        if (t.contains("PICKAXE")) {
            return ToolFamily.PICKAXE;
        }
        if (t.contains("_AXE")) {
            return ToolFamily.AXE;
        }
        if (t.contains("SHOVEL")) {
            return ToolFamily.SHOVEL;
        }
        if (t.contains("HOE")) {
            return ToolFamily.HOE;
        }
        return ToolFamily.OTHER;
    }

    /**
     * Effective (fastest) tool family for a block type, or null when the block
     * is not recognized by the family tables (modded mechanics).
     */
    public static ToolFamily effectiveFamilyForBlock(String blockName) {
        String block = blockName == null ? "" : blockName;
        if (containsAny(block, PICKAXE_BLOCKS)) {
            return ToolFamily.PICKAXE;
        }
        if (containsAny(block, AXE_BLOCKS)) {
            return ToolFamily.AXE;
        }
        if (containsAny(block, SHOVEL_BLOCKS)) {
            return ToolFamily.SHOVEL;
        }
        if (containsAny(block, HOE_BLOCKS)) {
            return ToolFamily.HOE;
        }
        return null;
    }

    /** True when this model recognizes the block's vanilla mining mechanics. */
    public static boolean isBlockRecognized(String blockName) {
        return effectiveFamilyForBlock(blockName) != null;
    }

    /** True when the held tool's family is effective for the block (base dig speed applies). */
    public static boolean toolEffective(String blockName, String toolName) {
        ToolFamily block = effectiveFamilyForBlock(blockName);
        return block != null && block == toolFamily(toolName);
    }

    /**
     * Pickaxe tier required to harvest the block's drop, or {@link HarvestTier#NONE}.
     * Unrecognized pickaxe-family blocks default to {@link HarvestTier#DIAMOND}
     * (quietest honest choice); unrecognized other blocks drop without a tool.
     */
    public static HarvestTier requiredHarvest(String blockName) {
        String block = blockName == null ? "" : blockName;
        if (containsAny(block, NO_TOOL_REQUIRED)) {
            return HarvestTier.NONE;
        }
        if (containsAny(block, REQUIRES_DIAMOND_PICKAXE)) {
            return HarvestTier.DIAMOND;
        }
        if (containsAny(block, REQUIRES_IRON_PICKAXE)) {
            return HarvestTier.IRON;
        }
        if (containsAny(block, REQUIRES_STONE_PICKAXE)) {
            return HarvestTier.STONE;
        }
        if (containsAny(block, REQUIRES_ANY_PICKAXE)) {
            return HarvestTier.WOOD;
        }
        return effectiveFamilyForBlock(block) == ToolFamily.PICKAXE ? HarvestTier.DIAMOND : HarvestTier.NONE;
    }

    /**
     * Vanilla {@code canHarvest}: true when the block drops without any tool,
     * or the held item is a pickaxe of sufficient tier. Modded/unmapped tools
     * never satisfy a requirement (rank 0 or unknown), so requirements fall
     * back to the 100-tick divisor — quietest honest choice.
     */
    public static boolean canHarvest(String blockName, String toolName) {
        HarvestTier required = requiredHarvest(blockName);
        if (required == HarvestTier.NONE) {
            return true;
        }
        if (toolFamily(toolName) != ToolFamily.PICKAXE) {
            return false;
        }
        Integer rank = pickaxeRank(toolName);
        return rank != null && rank >= harvestRank(required);
    }

    /** Pickaxe tier rank by material name; null when the tool is not a mapped vanilla pickaxe (or is not a pickaxe). */
    public static Integer pickaxeRank(String toolName) {
        String t = toolName == null ? "" : toolName;
        if (!t.contains("PICKAXE")) {
            return null;
        }
        if (t.contains("NETHERITE_")) {
            return 4;
        }
        if (t.contains("DIAMOND_")) {
            return 3;
        }
        if (t.contains("IRON_")) {
            return 2;
        }
        if (t.contains("STONE_")) {
            return 1;
        }
        if (t.contains("WOODEN_") || t.contains("GOLDEN_") || t.contains("GOLD_")) {
            return 0; // gold pickaxe: fast but stone-equivalent tier 0 in vanilla
        }
        return null; // e.g. copper: not mapped in the pinned API — never guessed
    }

    private static int harvestRank(HarvestTier tier) {
        switch (tier) {
            case WOOD:
                return 0;
            case STONE:
                return 1;
            case IRON:
                return 2;
            case DIAMOND:
            default:
                return 3;
        }
    }

    /**
     * Full expected dig time (ms) with every applicable modifier applied.
     *
     * @return NaN when no timing verdict may be taken (unknown block mechanics,
     *         or an effective-but-unmapped tool); 0 for instant materials;
     *         otherwise the expected duration.
     */
    public static double expectedMillis(double hardness, boolean toolEffective, boolean canHarvest,
                                        boolean miningModelKnown, boolean toolSpeedKnown,
                                        double toolSpeed, int efficiency, int hasteLevel, int miningFatigueLevel,
                                        boolean inWater, boolean hasAquaAffinity, boolean onGround) {
        if (hardness <= 0.0D) {
            return 0.0D; // hardness ≤ 0 breaks instantly for everyone — universal vanilla rule
        }
        if (!miningModelKnown) {
            return Double.NaN; // unknown block mechanics: never a timing verdict
        }
        if (toolEffective && !toolSpeedKnown) {
            return Double.NaN; // effective but unmappable tool: cannot judge timing honestly
        }
        int eff = clamp(efficiency, 0, MAX_EFFICIENCY);
        double speed = toolEffective
                ? toolSpeed + (eff > 0 ? (double) eff * eff + 1.0D : 0.0D)
                : 1.0D;
        int haste = clamp(hasteLevel, 0, MAX_HASTE);
        if (haste > 0) {
            speed *= 1.0D + 0.2D * haste;
        }
        int fatigue = clamp(miningFatigueLevel, 0, MAX_FATIGUE);
        if (fatigue > 0) {
            speed *= Math.pow(0.3D, fatigue);
        }
        if (inWater && !hasAquaAffinity) {
            speed *= 0.2D;
        }
        if (!onGround) {
            speed *= 0.2D;
        }
        return hardness * (canHarvest ? 1.5D : 5.0D) / speed * 1_000.0D;
    }

    /**
     * Hard lower bound (ms): best-case vanilla dig with the given tool —
     * efficiency and haste included, no fatigue/water/air penalty. The
     * harvestability divisor still applies (an iron pickaxe on obsidian takes
     * ~41.7 s in vanilla, never less, at any speed).
     *
     * @return NaN when no verdict may be taken (unknown mechanics); 0 for instant materials.
     */
    public static double minimumMillis(double hardness, boolean toolEffective, boolean canHarvest,
                                       boolean miningModelKnown, boolean toolSpeedKnown,
                                       double toolSpeed, int efficiency, int hasteLevel) {
        if (hardness <= 0.0D) {
            return 0.0D;
        }
        if (!miningModelKnown) {
            return Double.NaN;
        }
        if (toolEffective && !toolSpeedKnown) {
            return Double.NaN;
        }
        int eff = clamp(efficiency, 0, MAX_EFFICIENCY);
        double speed = toolEffective
                ? toolSpeed + (eff > 0 ? (double) eff * eff + 1.0D : 0.0D)
                : 1.0D;
        int haste = clamp(hasteLevel, 0, MAX_HASTE);
        if (haste > 0) {
            speed *= 1.0D + 0.2D * haste;
        }
        return hardness * (canHarvest ? 1.5D : 5.0D) / speed * 1_000.0D;
    }

    private static boolean containsAny(String text, String[] markers) {
        for (String marker : markers) {
            if (text.contains(marker)) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    // ---------------------------------------------------------------- tables
    // Effective-family markers (which tool digs this block fastest), moved
    // verbatim from the listener's Phase-6 heuristic.

    private static final String[] PICKAXE_BLOCKS = {
        "STONE", "DEEPSLATE", "BLACKSTONE", "BASALT", "ANDESITE", "DIORITE", "GRANITE",
        "TUFF", "ORE", "ANCIENT_DEBRIS", "TERRACOTTA", "CONCRETE", "BRICK", "OBSIDIAN",
        "NETHERRACK", "PRISMARINE", "PURPUR", "END_STONE", "AMETHYST", "GLOWSTONE",
        "SANDSTONE", "RED_SANDSTONE", "MOSSY_COBBLESTONE", "COBBLED_DEEPSLATE", "SCULK"
    };

    private static final String[] AXE_BLOCKS = {
        "LOG", "WOOD", "STEM", "HYPHAE", "PLANK", "BAMBOO", "FENCE", "DOOR", "TRAPDOOR",
        "SIGN", "CHEST", "BARREL", "CRAFTING_TABLE", "LADDER", "BUTTON", "PRESSURE_PLATE"
    };

    private static final String[] SHOVEL_BLOCKS = {
        "DIRT", "GRASS_BLOCK", "PODZOL", "MYCELIUM", "ROOTED_DIRT", "COARSE_DIRT",
        "SAND", "GRAVEL", "CLAY", "SOUL_SAND", "SOUL_SOIL", "SNOW", "MUD", "SOD"
    };

    private static final String[] HOE_BLOCKS = {
        "LEAVES", "HAY_BLOCK", "WART_BLOCK", "MOSS_BLOCK", "DRIED_KELP", "SHROOMLIGHT"
    };

    // Required-pickaxe-tier tables for DROPS (vanilla 1.16 through 1.21.x and 26.x; unchanged there).

    /** Drop requires a diamond-tier pickaxe or better. */
    private static final String[] REQUIRES_DIAMOND_PICKAXE = {
        "OBSIDIAN",       // covers CRYING_OBSIDIAN as well
        "ANCIENT_DEBRIS",
        "NETHERITE_BLOCK",
        "RESPAWN_ANCHOR",
        "ENCHANTING_TABLE"
    };

    /** Drop requires an iron-tier pickaxe or better (covers deepslate variants via substring). */
    private static final String[] REQUIRES_IRON_PICKAXE = {
        "GOLD_ORE", "REDSTONE_ORE", "DIAMOND_ORE", "EMERALD_ORE",
        "GOLD_BLOCK", "DIAMOND_BLOCK", "EMERALD_BLOCK",
        "RAW_GOLD_BLOCK"
    };

    /** Drop requires a stone-tier pickaxe or better (covers deepslate variants via substring). */
    private static final String[] REQUIRES_STONE_PICKAXE = {
        "IRON_ORE", "LAPIS_ORE", "COPPER_ORE",
        "RAW_IRON_BLOCK", "RAW_COPPER_BLOCK",
        "IRON_BLOCK", "LAPIS_BLOCK"
    };

    /** Pickaxe is merely the fastest tool; these blocks drop without any tool. */
    private static final String[] NO_TOOL_REQUIRED = {
        "SANDSTONE", "RED_SANDSTONE", "GLOWSTONE", "NETHERRACK",
        "MAGMA_BLOCK", "SEA_LANTERN", "NETHER_GOLD_ORE"
    };

    /** Recognized pickaxe-family blocks whose drop only needs any pickaxe. */
    private static final String[] REQUIRES_ANY_PICKAXE = {
        "STONE", "COBBLE", "DEEPSLATE", "GRANITE", "DIORITE", "ANDESITE", "TUFF",
        "BLACKSTONE", "BASALT", "DRIPSTONE_BLOCK", "BRICKS", "MOSSY", "PRISMARINE",
        "PURPUR", "END_STONE", "AMETHYST_BLOCK", "COAL_ORE", "NETHER_QUARTZ_ORE",
        "TERRACOTTA", "FURNACE", "CONCRETE", "SCULK",
        "REDSTONE_BLOCK", "QUARTZ"
    };

    private BreakTimeModel() {
    }
}
