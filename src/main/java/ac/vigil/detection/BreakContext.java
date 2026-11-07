package ac.vigil.detection;

/**
 * Immutable snapshot of a completed block break, built from a main-thread
 * {@code BlockBreakEvent}. Carries the mining facts vanilla actually uses
 * (tool-family effectiveness, drop-harvestability, block-mechanics
 * recognition, efficiency level on the held item, haste amplifier) so checks
 * can compute a physically impossible break-time budget without guessing.
 * Unknown block mechanics or an effective-but-unmapped tool produce NaN
 * budgets — "cannot judge", never a guess.
 */
public final class BreakContext {

    private final java.util.UUID player;
    private final String blockType;
    private final int blockX, blockY, blockZ;
    /** Block hardness from the server (0 = instant, negative = unbreakable). */
    private final float hardness;
    /** True when the held tool's family is effective for this block (base dig speed and Efficiency apply). */
    private final boolean toolEffective;
    /** True when the held tool satisfies the block's drop requirement (30-tick vs 100-tick divisor). */
    private final boolean canHarvest;
    /** False when the block is not recognized by the vanilla mining model (modded mechanics): no timing verdict. */
    private final boolean miningModelKnown;
    /** Efficiency enchantment level on the held item, 0 if none. */
    private final int efficiency;
    /** Haste potion amplifier + 1, 0 if none. Haste boosts dig speed by 20% per level. */
    private final int hasteLevel;
    private final int pingMillis;
    private final long timestamp;
    private final int miningFatigueLevel;
    private final boolean inWater;
    private final boolean hasAquaAffinity;
    private final boolean onGround;
    /** Wall-clock dig duration from the digging lifecycle (START_DIGGING → FINISHED_DIGGING); -1 when unknown. */
    private final long digDurationMillis;
    /** True when the held item or destroy direction changed mid-dig: tool timing is then inconclusive. */
    private final boolean digInconclusive;
    /** Base dig speed of the held tool family; 1.0 (hand speed) when built without tool data (test convenience). */
    private final double toolSpeed;
    /** False when the tool tier could not be mapped to a known vanilla family — timing verdicts are suppressed. */
    private final boolean toolSpeedKnown;

    private BreakContext(Builder b) {
        this.player = b.player;
        this.blockType = b.blockType == null ? "" : b.blockType;
        this.blockX = b.blockX;
        this.blockY = b.blockY;
        this.blockZ = b.blockZ;
        this.hardness = b.hardness;
        this.toolEffective = b.toolEffective;
        this.canHarvest = b.canHarvest;
        this.miningModelKnown = b.miningModelKnown;
        this.efficiency = b.efficiency;
        this.hasteLevel = b.hasteLevel;
        this.pingMillis = b.pingMillis;
        this.timestamp = b.timestamp;
        this.miningFatigueLevel = b.miningFatigueLevel;
        this.inWater = b.inWater;
        this.hasAquaAffinity = b.hasAquaAffinity;
        this.onGround = b.onGround;
        this.digDurationMillis = b.digDurationMillis;
        this.digInconclusive = b.digInconclusive;
        this.toolSpeed = b.toolSpeed;
        this.toolSpeedKnown = b.toolSpeedKnown;
    }

    public java.util.UUID getPlayer() { return player; }
    public String getBlockType() { return blockType; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }
    public float getHardness() { return hardness; }
    public boolean isToolEffective() { return toolEffective; }
    /** True when the held tool satisfies the block's drop requirement (30-tick divisor applies). */
    public boolean isCanHarvest() { return canHarvest; }
    /** False when the block is not recognized by the vanilla mining model: both budgets are NaN. */
    public boolean isMiningModelKnown() { return miningModelKnown; }
    public int getEfficiency() { return efficiency; }
    public int getHasteLevel() { return hasteLevel; }
    public int getPingMillis() { return pingMillis; }
    public long getTimestamp() { return timestamp; }
    public int getMiningFatigueLevel() { return miningFatigueLevel; }
    public boolean isInWater() { return inWater; }
    public boolean hasAquaAffinity() { return hasAquaAffinity; }
    public boolean isOnGround() { return onGround; }
    /** Dig duration from the packet digging lifecycle, or -1 when packetevents is absent / the dig was not tracked. */
    public long getDigDurationMillis() { return digDurationMillis; }
    /** True when tool/destroy direction changed mid-dig; timing evidence is inconclusive. */
    public boolean isDigInconclusive() { return digInconclusive; }
    /** Base dig speed of the held tool; 1.0 when the context was built without tool data. */
    public double getToolSpeed() { return toolSpeed; }
    /** False when the tool tier is unknown (e.g. modded, copper-era, none): timing verdicts must be suppressed. */
    public boolean isToolSpeedKnown() { return toolSpeedKnown; }

    /**
     * Minimum number of milliseconds vanilla requires to break this block
     * with the provided tool facts (efficiency and haste included; the
     * harvestability divisor applies — an iron pickaxe on obsidian takes
     * never less than ~41.7 s). NaN when suppressed (unknown block mechanics,
     * or effective-but-unmapped tool). Used as a hard lower bound:
     * breaking faster than this is impossible.
     */
    public double minimumBreakMillis() {
        return BreakTimeModel.minimumMillis(hardness, toolEffective, canHarvest, miningModelKnown,
                toolSpeedKnown, toolSpeed, efficiency, hasteLevel);
    }

    /**
     * Expected break duration (ms) from dig start using the full vanilla
     * modifier stack (see {@link BreakTimeModel}). Compared against the
     * observed dig lifecycle, not the completion-to-completion interval.
     * NaN when suppressed (unknown block mechanics or effective-but-unmapped
     * tool — no timing verdict).
     */
    public double expectedBreakMillis() {
        return BreakTimeModel.expectedMillis(hardness, toolEffective, canHarvest, miningModelKnown,
                toolSpeedKnown, toolSpeed, efficiency, hasteLevel, miningFatigueLevel, inWater, hasAquaAffinity, onGround);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private java.util.UUID player;
        private String blockType = "";
        private int blockX, blockY, blockZ;
        private float hardness;
        private boolean toolEffective = true;
        private boolean canHarvest = true;
        private boolean miningModelKnown = true;
        private int efficiency;
        private int hasteLevel;
        private int pingMillis;
        private long timestamp = System.currentTimeMillis();
        private int miningFatigueLevel;
        private boolean inWater;
        private boolean hasAquaAffinity;
        private boolean onGround = true;
        private long digDurationMillis = -1L;
        private boolean digInconclusive;
        private double toolSpeed = 1.0D; // bare-hand speed: most conservative (longest expected time) when no tool data is supplied
        private boolean toolSpeedKnown = true;

        public Builder player(java.util.UUID player) { this.player = player; return this; }
        public Builder blockType(String blockType) { this.blockType = blockType; return this; }
        public Builder block(int x, int y, int z) { this.blockX = x; this.blockY = y; this.blockZ = z; return this; }
        public Builder hardness(float hardness) { this.hardness = hardness; return this; }
        public Builder toolEffective(boolean toolEffective) { this.toolEffective = toolEffective; return this; }
        public Builder canHarvest(boolean canHarvest) { this.canHarvest = canHarvest; return this; }
        public Builder miningModelKnown(boolean miningModelKnown) { this.miningModelKnown = miningModelKnown; return this; }
        public Builder efficiency(int efficiency) { this.efficiency = efficiency; return this; }
        public Builder hasteLevel(int hasteLevel) { this.hasteLevel = hasteLevel; return this; }
        public Builder pingMillis(int pingMillis) { this.pingMillis = pingMillis; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder miningFatigueLevel(int miningFatigueLevel) { this.miningFatigueLevel = miningFatigueLevel; return this; }
        public Builder inWater(boolean inWater) { this.inWater = inWater; return this; }
        public Builder aquaAffinity(boolean hasAquaAffinity) { this.hasAquaAffinity = hasAquaAffinity; return this; }
        public Builder onGround(boolean onGround) { this.onGround = onGround; return this; }
        public Builder digDurationMillis(long digDurationMillis) { this.digDurationMillis = digDurationMillis; return this; }
        public Builder digInconclusive(boolean digInconclusive) { this.digInconclusive = digInconclusive; return this; }
        /** Held item is a known vanilla tool family with base speed {@code toolSpeed}. */
        public Builder toolSpeed(double toolSpeed) { this.toolSpeed = toolSpeed; this.toolSpeedKnown = true; return this; }
        /** Maps a nullable tier speed: null = timing verdicts suppressed, otherwise the known base speed. */
        public Builder toolSpeedOrUnknown(Double toolSpeed) {
            if (toolSpeed == null) {
                this.toolSpeed = 1.0D;
                this.toolSpeedKnown = false;
            } else {
                this.toolSpeed = toolSpeed;
                this.toolSpeedKnown = true;
            }
            return this;
        }
        /** Tool tier could not be mapped reliably: timing verdicts are suppressed (never guessed). */
        public Builder toolSpeedUnknown() { this.toolSpeed = 1.0D; this.toolSpeedKnown = false; return this; }

        public BreakContext build() {
            if (player == null) {
                player = new java.util.UUID(0L, 0L);
            }
            return new BreakContext(this);
        }
    }
}
