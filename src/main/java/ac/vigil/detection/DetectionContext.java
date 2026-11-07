package ac.vigil.detection;

/**
 * Immutable snapshot of a player's movement state at a single instant.
 *
 * <p>This class deliberately has no dependency on Bukkit/Paper so detection
 * logic can be unit tested without a server. Callers (listeners) are
 * responsible for translating platform objects into this snapshot.</p>
 *
 * <p>Distances are blocks, angles are degrees, times are milliseconds since
 * the epoch ({@link System#currentTimeMillis()}).</p>
 */
public final class DetectionContext {

    private final java.util.UUID player;
    private final String worldName;

    private final double fromX, fromY, fromZ;
    private final double toX, toY, toZ;

    private final double deltaX, deltaY, deltaZ;
    private final double horizontalDistance;
    private final double verticalDistance;

    private final float yaw, pitch;
    private final boolean onGround;
    private final boolean sprinting;
    private final boolean sneaking;
    private final boolean gliding;      // elytra
    private final boolean inVehicle;
    private final boolean inLiquid;
    private final boolean onSlipperyBlock; // ice / slime family at feet
    private final boolean teleportGrace;   // just teleported (server-side or relative)
    private final boolean knockbackGrace;  // inside the post-velocity window
    private final int speedPotionLevel;    // 0 = none, 1 = Speed I, 2 = Speed II, ...
    private final int pingMillis;
    private final double serverTps;
    private final long timestamp;
    private final double movePacketsPerSecond;
    private final boolean levitation;
    private final boolean slowFalling;
    private final boolean inWeb;
    /** Standing on a block that slows horizontal movement (soul sand/soil, honey, berry bush). */
    private final boolean onSlowBlock;
    /** Feet are NOT in liquid but the block directly below is water — the "walking on water" signature. */
    private final boolean onWaterSurface;
    /** Feet equipment carries the Frost Walker enchantment (vanilla legal water walking). */
    private final boolean frostWalker;
    /**
     * The block directly under the player's feet (at the start or the end of
     * this movement) is slime/ice/honey-family. Used by slime-bounce analysis:
     * on the tick a sneak-charged bounce leaves the block, the feet have
     * already left it, so an at-feet read alone misses the charge.
     */
    private final boolean belowFeetSlippery;
    /** Player is charging/riding a riptide trident (LivingEntity#isRiptiding). Vanilla fast water-skip motion otherwise reads as jesus. */
    private final boolean riptiding;
    /** Inside the bounded (<=3 s) vertical-impulse grace after a wind-burst-class velocity event (e.g. mace slam). */
    private final boolean windBurstGrace;

    private DetectionContext(Builder b) {
        this.player = b.player;
        this.worldName = b.worldName;
        this.fromX = b.fromX;
        this.fromY = b.fromY;
        this.fromZ = b.fromZ;
        this.toX = b.toX;
        this.toY = b.toY;
        this.toZ = b.toZ;
        this.deltaX = b.toX - b.fromX;
        this.deltaY = b.toY - b.fromY;
        this.deltaZ = b.toZ - b.fromZ;
        this.horizontalDistance = Math.hypot(this.deltaX, this.deltaZ);
        this.verticalDistance = Math.abs(this.deltaY);
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.onGround = b.onGround;
        this.sprinting = b.sprinting;
        this.sneaking = b.sneaking;
        this.gliding = b.gliding;
        this.inVehicle = b.inVehicle;
        this.inLiquid = b.inLiquid;
        this.onSlipperyBlock = b.onSlipperyBlock;
        this.teleportGrace = b.teleportGrace;
        this.knockbackGrace = b.knockbackGrace;
        this.speedPotionLevel = b.speedPotionLevel;
        this.pingMillis = b.pingMillis;
        this.serverTps = b.serverTps;
        this.timestamp = b.timestamp;
        this.movePacketsPerSecond = b.movePacketsPerSecond;
        this.levitation = b.levitation;
        this.slowFalling = b.slowFalling;
        this.inWeb = b.inWeb;
        this.onSlowBlock = b.onSlowBlock;
        this.onWaterSurface = b.onWaterSurface;
        this.frostWalker = b.frostWalker;
        this.belowFeetSlippery = b.belowFeetSlippery;
        this.riptiding = b.riptiding;
        this.windBurstGrace = b.windBurstGrace;
    }

    public java.util.UUID getPlayer() { return player; }
    public String getWorldName() { return worldName; }
    public double getFromX() { return fromX; }
    public double getFromY() { return fromY; }
    public double getFromZ() { return fromZ; }
    public double getToX() { return toX; }
    public double getToY() { return toY; }
    public double getToZ() { return toZ; }
    public double getDeltaX() { return deltaX; }
    public double getDeltaY() { return deltaY; }
    public double getDeltaZ() { return deltaZ; }
    public double getHorizontalDistance() { return horizontalDistance; }
    public double getVerticalDistance() { return verticalDistance; }
    public float getYaw() { return yaw; }
    public float getPitch() { return pitch; }
    public boolean isOnGround() { return onGround; }
    public boolean isSprinting() { return sprinting; }
    public boolean isSneaking() { return sneaking; }
    public boolean isGliding() { return gliding; }
    public boolean isInVehicle() { return inVehicle; }
    public boolean isInLiquid() { return inLiquid; }
    public boolean isOnSlipperyBlock() { return onSlipperyBlock; }
    public boolean isTeleportGrace() { return teleportGrace; }
    public boolean isKnockbackGrace() { return knockbackGrace; }
    public int getSpeedPotionLevel() { return speedPotionLevel; }
    public int getPingMillis() { return pingMillis; }
    public double getServerTps() { return serverTps; }
    public long getTimestamp() { return timestamp; }
    public double getMovePacketsPerSecond() { return movePacketsPerSecond; }
    /** True while the Levitation potion effect is active (vanilla upward motion otherwise indistinguishable from fly). */
    public boolean isLevitation() { return levitation; }
    /** True while Slow Falling is active (vanilla reduced gravity otherwise indistinguishable from hover). */
    public boolean isSlowFalling() { return slowFalling; }
    /** Feet or head is inside a cobweb (vanilla heavy drag on all motion). */
    public boolean isInWeb() { return inWeb; }
    /** Standing on a movement-slowing block (soul sand/soil, honey, berry bush). */
    public boolean isOnSlowBlock() { return onSlowBlock; }
    /** Feet are dry but the block below is water (vanilla-impossible without Frost Walker). */
    public boolean isOnWaterSurface() { return onWaterSurface; }
    /** Boots carry Frost Walker — vanilla legal walking on water. */
    public boolean hasFrostWalker() { return frostWalker; }
    /** Block directly under the player's feet (at either end of the tick) is slippery-family; slime bounce evidence. */
    public boolean isBelowFeetSlippery() { return belowFeetSlippery; }
    /** True while the riptide trident impulse is active (vanilla fast water-skip motion). */
    public boolean isRiptiding() { return riptiding; }
    /** True inside the bounded vertical-impulse grace granted after a wind-burst-class velocity event. */
    public boolean isWindBurstGrace() { return windBurstGrace; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private java.util.UUID player;
        private String worldName = "";
        private double fromX, fromY, fromZ, toX, toY, toZ;
        private float yaw, pitch;
        private boolean onGround, sprinting, sneaking, gliding, inVehicle, inLiquid, onSlipperyBlock;
        private boolean teleportGrace, knockbackGrace;
        private int speedPotionLevel;
        private int pingMillis;
        private double serverTps = 20.0D;
        private long timestamp = System.currentTimeMillis();
        private double movePacketsPerSecond = 20.0D;
        private boolean levitation, slowFalling;
        private boolean inWeb;
        private boolean onSlowBlock;
        private boolean onWaterSurface;
        private boolean frostWalker;
        private boolean belowFeetSlippery;
        private boolean riptiding;
        private boolean windBurstGrace;

        public Builder player(java.util.UUID player) { this.player = player; return this; }
        public Builder worldName(String worldName) { this.worldName = worldName == null ? "" : worldName; return this; }
        public Builder from(double x, double y, double z) { this.fromX = x; this.fromY = y; this.fromZ = z; return this; }
        public Builder to(double x, double y, double z) { this.toX = x; this.toY = y; this.toZ = z; return this; }
        public Builder yaw(float yaw) { this.yaw = yaw; return this; }
        public Builder pitch(float pitch) { this.pitch = pitch; return this; }
        public Builder onGround(boolean onGround) { this.onGround = onGround; return this; }
        public Builder sprinting(boolean sprinting) { this.sprinting = sprinting; return this; }
        public Builder sneaking(boolean sneaking) { this.sneaking = sneaking; return this; }
        public Builder gliding(boolean gliding) { this.gliding = gliding; return this; }
        public Builder inVehicle(boolean inVehicle) { this.inVehicle = inVehicle; return this; }
        public Builder inLiquid(boolean inLiquid) { this.inLiquid = inLiquid; return this; }
        public Builder onSlipperyBlock(boolean onSlipperyBlock) { this.onSlipperyBlock = onSlipperyBlock; return this; }
        public Builder teleportGrace(boolean teleportGrace) { this.teleportGrace = teleportGrace; return this; }
        public Builder knockbackGrace(boolean knockbackGrace) { this.knockbackGrace = knockbackGrace; return this; }
        public Builder speedPotionLevel(int speedPotionLevel) { this.speedPotionLevel = speedPotionLevel; return this; }
        public Builder pingMillis(int pingMillis) { this.pingMillis = pingMillis; return this; }
        public Builder serverTps(double serverTps) { this.serverTps = serverTps; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder movePacketsPerSecond(double movePacketsPerSecond) { this.movePacketsPerSecond = movePacketsPerSecond; return this; }
        public Builder levitation(boolean levitation) { this.levitation = levitation; return this; }
        public Builder slowFalling(boolean slowFalling) { this.slowFalling = slowFalling; return this; }
        public Builder inWeb(boolean inWeb) { this.inWeb = inWeb; return this; }
        public Builder onSlowBlock(boolean onSlowBlock) { this.onSlowBlock = onSlowBlock; return this; }
        public Builder onWaterSurface(boolean onWaterSurface) { this.onWaterSurface = onWaterSurface; return this; }
        public Builder frostWalker(boolean frostWalker) { this.frostWalker = frostWalker; return this; }
        public Builder belowFeetSlippery(boolean belowFeetSlippery) { this.belowFeetSlippery = belowFeetSlippery; return this; }
        public Builder riptiding(boolean riptiding) { this.riptiding = riptiding; return this; }
        public Builder windBurstGrace(boolean windBurstGrace) { this.windBurstGrace = windBurstGrace; return this; }

        public DetectionContext build() {
            if (player == null) {
                player = new java.util.UUID(0L, 0L);
            }
            return new DetectionContext(this);
        }
    }
}
