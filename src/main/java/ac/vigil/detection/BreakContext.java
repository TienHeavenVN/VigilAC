package ac.vigil.detection;

/**
 * Immutable snapshot of a completed block break, built from a main-thread
 * {@code BlockBreakEvent}. Carries the tool facts the server actually used
 * (tool family correctness for the block, efficiency enchant level on the
 * held item, haste potion amplifier) so checks can compute a physically
 * impossible break-time budget without guessing.
 */
public final class BreakContext {

    private final java.util.UUID player;
    private final String blockType;
    private final int blockX, blockY, blockZ;
    /** Block hardness from the server (0 = instant, negative = unbreakable). */
    private final float hardness;
    /** True when the held tool is the correct family for this block (or the block needs none). */
    private final boolean correctTool;
    /** Efficiency enchantment level on the held item, 0 if none. */
    private final int efficiency;
    /** Haste potion amplifier + 1, 0 if none. Haste boosts dig speed by 20% per level. */
    private final int hasteLevel;
    private final int pingMillis;
    private final long timestamp;

    private BreakContext(Builder b) {
        this.player = b.player;
        this.blockType = b.blockType == null ? "" : b.blockType;
        this.blockX = b.blockX;
        this.blockY = b.blockY;
        this.blockZ = b.blockZ;
        this.hardness = b.hardness;
        this.correctTool = b.correctTool;
        this.efficiency = b.efficiency;
        this.hasteLevel = b.hasteLevel;
        this.pingMillis = b.pingMillis;
        this.timestamp = b.timestamp;
    }

    public java.util.UUID getPlayer() { return player; }
    public String getBlockType() { return blockType; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }
    public float getHardness() { return hardness; }
    public boolean isCorrectTool() { return correctTool; }
    public int getEfficiency() { return efficiency; }
    public int getHasteLevel() { return hasteLevel; }
    public int getPingMillis() { return pingMillis; }
    public long getTimestamp() { return timestamp; }

    /**
     * Minimum number of milliseconds vanilla requires to break this block
     * with the provided tool facts, assuming best-case play (efficiency and
     * haste included, haste capped at level 3). Used as a hard lower bound:
     * breaking faster than this is impossible.
     */
    public double minimumBreakMillis() {
        if (hardness <= 0.0F) {
            return 0.0D; // instant-break material
        }
        if (!correctTool) {
            return (double) hardness * 5.0D * 1_000.0D; // wrong tool: 5x divisor
        }
        int eff = Math.max(0, Math.min(5, efficiency)); // vanilla efficiency caps at V
        double speed = 1.0D + (eff + 1) * (eff + 1);
        int haste = Math.max(0, Math.min(3, hasteLevel));
        speed *= 1.0D + 0.2D * haste;
        return (double) hardness * 1.5D / speed * 1_000.0D;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private java.util.UUID player;
        private String blockType = "";
        private int blockX, blockY, blockZ;
        private float hardness;
        private boolean correctTool = true;
        private int efficiency;
        private int hasteLevel;
        private int pingMillis;
        private long timestamp = System.currentTimeMillis();

        public Builder player(java.util.UUID player) { this.player = player; return this; }
        public Builder blockType(String blockType) { this.blockType = blockType; return this; }
        public Builder block(int x, int y, int z) { this.blockX = x; this.blockY = y; this.blockZ = z; return this; }
        public Builder hardness(float hardness) { this.hardness = hardness; return this; }
        public Builder correctTool(boolean correctTool) { this.correctTool = correctTool; return this; }
        public Builder efficiency(int efficiency) { this.efficiency = efficiency; return this; }
        public Builder hasteLevel(int hasteLevel) { this.hasteLevel = hasteLevel; return this; }
        public Builder pingMillis(int pingMillis) { this.pingMillis = pingMillis; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

        public BreakContext build() {
            if (player == null) {
                player = new java.util.UUID(0L, 0L);
            }
            return new BreakContext(this);
        }
    }
}
