package ac.vigil.detection;

/**
 * Pure-Java vanilla break-time model (no Bukkit). Central place every number
 * lives, shared by {@link BreakContext}'s budgets and unit tests.
 *
 * <h2>Formula (vanilla 1.20+ semantics as far as applicable to 1.21.8)</h2>
 * <ul>
 *   <li>Correct-tool dig speed: {@code speed = toolSpeed + (eff > 0 ? eff² + 1 : 0)},
 *       where {@code toolSpeed} is the tool-family base speed (golden 12,
 *       netherite 9, diamond 8, iron 6, stone 4, wooden 2). Hands/unknown
 *       tools have no base speed — see suppression below.</li>
 *   <li>Wrong tool (or block needs none and none held): base speed 1 and the
 *       duration uses the 5.0 divisor (&asymp; vanilla hardness × 30 / 6.0).</li>
 *   <li>Haste multiplies speed by {@code 1 + 0.2 × level} (cap 4).</li>
 *   <li>Mining Fatigue multiplies speed by {@code 0.3 ^ level} (cap 4).</li>
 *   <li>Underwater without Aqua Affinity multiplies by 0.2.</li>
 *   <li>Off-ground multiplies by 0.2.</li>
 *   <li>{@code expectedMillis = hardness × (correctTool ? 1.5 : 5.0) / speed × 1000};
 *       0-hardness materials break instantly (0 ms).</li>
 * </ul>
 *
 * <h2>Honesty rule: no invented tool speeds</h2>
 * When the held item cannot be mapped to a vanilla tool family (hand, hoe on
 * the wrong block family still yields “correct = false” so the wrong-tool path
 * applies; the suppression exists for correct-tool timing verdicts), the
 * timing <em>verdict</em> is suppressed: {@code NaN} budgets mean "cannot be
 * judged", never "fast". Unknown copper/modded tools are therefore silently
 * excluded from timing verdicts rather than assigned a guessed speed.
 *
 * <p>copper-tier tools shipped in recent snapshots: the plugin is compiled
 * against 1.21.8 where their speed is not part of the pinned API's contract,
 * so they intentionally resolve to UNKNOWN — see the compatibility matrix.</p>
 */
public final class BreakTimeModel {

    /** Hard vanilla efficiency cap (Efficiency V). */
    public static final int MAX_EFFICIENCY = 5;
    /** Hard haste cap used for the model (Haste II beacon-scale; level VI is not obtainable). */
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

    /**
     * Best-effort tool base speed for a held-item material name,
     * or null when no reliable value exists (caller suppresses the timing verdict).
     */
    public static Double toolSpeedFor(String materialName) {
        ToolTier tier = ToolTier.forMaterial(materialName);
        return tier == null ? null : tier.speed();
    }

    /**
     * Full expected dig time (ms) with every modifier applied.
     *
     * @return NaN when the verdict must be suppressed (correct tool, unknown speed);
     *         0 for instant materials; otherwise the expected duration.
     */
    public static double expectedMillis(double hardness, boolean correctTool, boolean toolSpeedKnown,
                                        double toolSpeed, int efficiency, int hasteLevel, int miningFatigueLevel,
                                        boolean inWater, boolean hasAquaAffinity, boolean onGround) {
        if (hardness <= 0.0D) {
            return 0.0D;
        }
        if (correctTool && !toolSpeedKnown) {
            return Double.NaN; // unknown tool tier: cannot judge timing honestly
        }
        int eff = clamp(efficiency, 0, MAX_EFFICIENCY);
        double speed = correctTool
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
        return hardness * (correctTool ? 1.5D : 5.0D) / speed * 1_000.0D;
    }

    /**
     * Hard lower bound (ms): best-case vanilla dig with the given tool —
     * efficiency and haste included, no fatigue/water/air penalty.
     *
     * @return NaN when suppressed (correct tool, unknown speed); 0 for instant materials.
     */
    public static double minimumMillis(double hardness, boolean correctTool, boolean toolSpeedKnown,
                                       double toolSpeed, int efficiency, int hasteLevel) {
        if (hardness <= 0.0D) {
            return 0.0D;
        }
        if (correctTool && !toolSpeedKnown) {
            return Double.NaN;
        }
        int eff = clamp(efficiency, 0, MAX_EFFICIENCY);
        double speed = correctTool
                ? toolSpeed + (eff > 0 ? (double) eff * eff + 1.0D : 0.0D)
                : 1.0D;
        int haste = clamp(hasteLevel, 0, MAX_HASTE);
        if (haste > 0) {
            speed *= 1.0D + 0.2D * haste;
        }
        return hardness * (correctTool ? 1.5D : 5.0D) / speed * 1_000.0D;
    }

    private static int clamp(int value, int lo, int hi) {
        return Math.max(lo, Math.min(hi, value));
    }

    private BreakTimeModel() {
    }
}
