package ac.vigil.detection;

/**
 * Immutable snapshot of a block-placement attempt, built from a main-thread
 * {@code BlockPlaceEvent}. Coordinates are absolute block positions; times
 * are milliseconds since the epoch.
 */
public final class PlaceContext {

    private final java.util.UUID player;
    private final String blockType;
    private final int blockX, blockY, blockZ;
    private final double feetX, feetY, feetZ;
    private final float yaw, pitch;
    private final boolean sprinting;
    private final boolean sneaking;
    private final boolean onGround;
    private final int pingMillis;
    private final long timestamp;

    private PlaceContext(Builder b) {
        this.player = b.player;
        this.blockType = b.blockType == null ? "" : b.blockType;
        this.blockX = b.blockX;
        this.blockY = b.blockY;
        this.blockZ = b.blockZ;
        this.feetX = b.feetX;
        this.feetY = b.feetY;
        this.feetZ = b.feetZ;
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.sprinting = b.sprinting;
        this.sneaking = b.sneaking;
        this.onGround = b.onGround;
        this.pingMillis = b.pingMillis;
        this.timestamp = b.timestamp;
    }

    public java.util.UUID getPlayer() { return player; }
    public String getBlockType() { return blockType; }
    public int getBlockX() { return blockX; }
    public int getBlockY() { return blockY; }
    public int getBlockZ() { return blockZ; }
    public double getFeetX() { return feetX; }
    public double getFeetY() { return feetY; }
    public double getFeetZ() { return feetZ; }
    /** Horizontal distance from feet to the placed block, XZ plane. */
    public double horizontalDistanceToBlock() {
        double cx = blockX + 0.5D;
        double cz = blockZ + 0.5D;
        return Math.hypot(cx - feetX, cz - feetZ);
    }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public boolean isSprinting() { return sprinting; }
    public boolean isSneaking() { return sneaking; }
    public boolean isOnGround() { return onGround; }
    public int getPingMillis() { return pingMillis; }
    public long getTimestamp() { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private java.util.UUID player;
        private String blockType = "";
        private int blockX, blockY, blockZ;
        private double feetX, feetY, feetZ;
        private float yaw, pitch;
        private boolean sprinting, sneaking, onGround;
        private int pingMillis;
        private long timestamp = System.currentTimeMillis();

        public Builder player(java.util.UUID player) { this.player = player; return this; }
        public Builder blockType(String blockType) { this.blockType = blockType; return this; }
        public Builder block(int x, int y, int z) { this.blockX = x; this.blockY = y; this.blockZ = z; return this; }
        public Builder feet(double x, double y, double z) { this.feetX = x; this.feetY = y; this.feetZ = z; return this; }
        public Builder yaw(float yaw) { this.yaw = yaw; return this; }
        public Builder pitch(float pitch) { this.pitch = pitch; return this; }
        public Builder sprinting(boolean sprinting) { this.sprinting = sprinting; return this; }
        public Builder sneaking(boolean sneaking) { this.sneaking = sneaking; return this; }
        public Builder onGround(boolean onGround) { this.onGround = onGround; return this; }
        public Builder pingMillis(int pingMillis) { this.pingMillis = pingMillis; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }

        public PlaceContext build() {
            if (player == null) {
                player = new java.util.UUID(0L, 0L);
            }
            return new PlaceContext(this);
        }
    }
}
