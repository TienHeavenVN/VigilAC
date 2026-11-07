package ac.vigil.monitor;

/**
 * Immutable movement observation for the monitor. Mirrors the fields of
 * {@code DetectionContext} but carries the monitor-relevant extras (game
 * mode, swim) and deliberately has no Bukkit dependency.
 */
public final class MoveSample {

    private final double x, y, z;
    private final float yaw, pitch;
    private final boolean onGround, sprinting, sneaking, gliding, swimming, inVehicle;
    private final boolean levitation, slowFalling;
    private final int speedPotionLevel;
    private final int pingMillis;
    private final double serverTps;
    private final String worldName;
    private final String gameMode;
    private final long timestamp;

    private MoveSample(Builder builder) {
        this.x = builder.x; this.y = builder.y; this.z = builder.z;
        this.yaw = builder.yaw; this.pitch = builder.pitch;
        this.onGround = builder.onGround;
        this.sprinting = builder.sprinting; this.sneaking = builder.sneaking;
        this.gliding = builder.gliding; this.swimming = builder.swimming;
        this.inVehicle = builder.inVehicle;
        this.levitation = builder.levitation; this.slowFalling = builder.slowFalling;
        this.speedPotionLevel = builder.speedPotionLevel;
        this.pingMillis = builder.pingMillis;
        this.serverTps = builder.serverTps;
        this.worldName = builder.worldName == null ? "" : builder.worldName;
        this.gameMode = builder.gameMode == null ? "" : builder.gameMode;
        this.timestamp = builder.timestamp;
    }

    public double x() { return x; }
    public double y() { return y; }
    public double z() { return z; }
    public float yaw() { return yaw; }
    public float pitch() { return pitch; }
    public boolean onGround() { return onGround; }
    public boolean sprinting() { return sprinting; }
    public boolean sneaking() { return sneaking; }
    public boolean gliding() { return gliding; }
    public boolean swimming() { return swimming; }
    public boolean inVehicle() { return inVehicle; }
    public boolean levitation() { return levitation; }
    public boolean slowFalling() { return slowFalling; }
    public int speedPotionLevel() { return speedPotionLevel; }
    public int pingMillis() { return pingMillis; }
    public double serverTps() { return serverTps; }
    public String worldName() { return worldName; }
    public String gameMode() { return gameMode; }
    public long timestamp() { return timestamp; }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private double x, y, z;
        private float yaw, pitch;
        private boolean onGround, sprinting, sneaking, gliding, swimming, inVehicle;
        private boolean levitation, slowFalling;
        private int speedPotionLevel;
        private int pingMillis = 20;
        private double serverTps = 20.0D;
        private String worldName = "";
        private String gameMode = "";
        private long timestamp;

        public Builder at(double x, double y, double z) { this.x = x; this.y = y; this.z = z; return this; }
        public Builder rotation(float yaw, float pitch) { this.yaw = yaw; this.pitch = pitch; return this; }
        public Builder onGround(boolean v) { this.onGround = v; return this; }
        public Builder sprinting(boolean v) { this.sprinting = v; return this; }
        public Builder sneaking(boolean v) { this.sneaking = v; return this; }
        public Builder gliding(boolean v) { this.gliding = v; return this; }
        public Builder swimming(boolean v) { this.swimming = v; return this; }
        public Builder inVehicle(boolean v) { this.inVehicle = v; return this; }
        public Builder levitation(boolean v) { this.levitation = v; return this; }
        public Builder slowFalling(boolean v) { this.slowFalling = v; return this; }
        public Builder speedPotionLevel(int v) { this.speedPotionLevel = v; return this; }
        public Builder pingMillis(int v) { this.pingMillis = v; return this; }
        public Builder serverTps(double v) { this.serverTps = v; return this; }
        public Builder worldName(String v) { this.worldName = v; return this; }
        public Builder gameMode(String v) { this.gameMode = v; return this; }
        public Builder timestamp(long v) { this.timestamp = v; return this; }
        public MoveSample build() { return new MoveSample(this); }
    }
}
