package ac.vigil.detection;

/**
 * Immutable snapshot of a single melee attack, built from a main-thread
 * {@code EntityDamageByEntityEvent}. Distances are blocks; times are
 * milliseconds since the epoch.
 */
public final class AttackContext {

    private final java.util.UUID attacker;
    private final String targetType;

    private final double eyeX, eyeY, eyeZ;
    private final double boxMinX, boxMinY, boxMinZ;
    private final double boxMaxX, boxMaxY, boxMaxZ;

    private final boolean creativeOrSpectator;
    private final boolean attackerInVehicle;
    private final boolean targetInVehicle;
    private final int pingMillis;
    private final long timestamp;
    private final String targetUniqueId;
    private final float yaw;
    private final float pitch;
    /** Item-specific interaction range read from the held item (e.g. a verified attack_range component); null = unknown, configured cap applies. */
    private final Double attackRangeMax;
    /** Where attackRangeMax came from: "component" or "unknown" (never guessed). */
    private final String attackRangeSource;
    /** Wall-clock time of the previous attack by this player, or -1 when unknown/first attack. */
    private final long previousAttackAtMillis;
    /** Target velocity components (blocks/tick as reported by the server). */
    private final double targetVelX, targetVelY, targetVelZ;
    /** When true, distance is compensated by the target's velocity over the attacker's ping (opt-in; default false). */
    private final boolean lagCompensationEnabled;
    /** Cap on the latency compensation, in blocks. */
    private final double lagCompensationMaxBlocks;

    private AttackContext(Builder b) {
        this.attacker = b.attacker;
        this.targetType = b.targetType;
        this.eyeX = b.eyeX;
        this.eyeY = b.eyeY;
        this.eyeZ = b.eyeZ;
        this.boxMinX = b.boxMinX;
        this.boxMinY = b.boxMinY;
        this.boxMinZ = b.boxMinZ;
        this.boxMaxX = b.boxMaxX;
        this.boxMaxY = b.boxMaxY;
        this.boxMaxZ = b.boxMaxZ;
        this.creativeOrSpectator = b.creativeOrSpectator;
        this.attackerInVehicle = b.attackerInVehicle;
        this.targetInVehicle = b.targetInVehicle;
        this.pingMillis = b.pingMillis;
        this.timestamp = b.timestamp;
        this.targetUniqueId = b.targetUniqueId == null ? "" : b.targetUniqueId;
        this.yaw = b.yaw;
        this.pitch = b.pitch;
        this.attackRangeMax = b.attackRangeMax;
        this.attackRangeSource = b.attackRangeSource;
        this.previousAttackAtMillis = b.previousAttackAtMillis;
        this.targetVelX = b.targetVelX;
        this.targetVelY = b.targetVelY;
        this.targetVelZ = b.targetVelZ;
        this.lagCompensationEnabled = b.lagCompensationEnabled;
        this.lagCompensationMaxBlocks = b.lagCompensationMaxBlocks;
    }

    public java.util.UUID getAttacker() { return attacker; }
    public String getTargetType() { return targetType; }
    public double getEyeX() { return eyeX; }
    public double getEyeY() { return eyeY; }
    public double getEyeZ() { return eyeZ; }
    public double getBoxMinX() { return boxMinX; }
    public double getBoxMinY() { return boxMinY; }
    public double getBoxMinZ() { return boxMinZ; }
    public double getBoxMaxX() { return boxMaxX; }
    public double getBoxMaxY() { return boxMaxY; }
    public double getBoxMaxZ() { return boxMaxZ; }
    public boolean isCreativeOrSpectator() { return creativeOrSpectator; }
    public boolean isAttackerInVehicle() { return attackerInVehicle; }
    public boolean isTargetInVehicle() { return targetInVehicle; }
    public int getPingMillis() { return pingMillis; }
    public long getTimestamp() { return timestamp; }
    /** Stable identity of the damaged entity (UUID string); empty if unavailable. */
    public String getTargetUniqueId() { return targetUniqueId; }
    /** Attacker rotation at the moment of the swing (degrees, MC convention). */
    public float getYaw() { return yaw; }
    /** Attacker pitch at the moment of the swing (degrees, negative = looking up). */
    public float getPitch() { return pitch; }
    /** Item-specific interaction range, or null when unknown (configured reach cap applies). */
    public Double getAttackRangeMax() { return attackRangeMax; }
    /** Origin of {@link #getAttackRangeMax()}: {@code component} or {@code unknown}. */
    public String getAttackRangeSource() { return attackRangeSource; }
    /** Wall-clock time of the previous attack by this player, or -1 when unknown. */
    public long getPreviousAttackAtMillis() { return previousAttackAtMillis; }
    public double getTargetVelX() { return targetVelX; }
    public double getTargetVelY() { return targetVelY; }
    public double getTargetVelZ() { return targetVelZ; }
    public boolean isLagCompensationEnabled() { return lagCompensationEnabled; }
    public double getLagCompensationMaxBlocks() { return lagCompensationMaxBlocks; }

    /**
     * Distance from the attacker's eye position to the closest point on the
     * target's axis-aligned bounding box. Implemented in doubles so it is
     * testable without a server.
     */
    public double distanceEyeToBox() {
        return distanceEyeToBox(eyeX, eyeY, eyeZ, boxMinX, boxMinY, boxMinZ,
                boxMaxX, boxMaxY, boxMaxZ);
    }

    /** Static form of {@link #distanceEyeToBox()} for callers that hold raw box coordinates. */
    public static double distanceEyeToBox(double eyeX, double eyeY, double eyeZ,
                                          double boxMinX, double boxMinY, double boxMinZ,
                                          double boxMaxX, double boxMaxY, double boxMaxZ) {
        double dx = Math.max(Math.max(boxMinX - eyeX, eyeX - boxMaxX), 0.0D);
        double dy = Math.max(Math.max(boxMinY - eyeY, eyeY - boxMaxY), 0.0D);
        double dz = Math.max(Math.max(boxMinZ - eyeZ, eyeZ - boxMaxZ), 0.0D);
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private java.util.UUID attacker;
        private String targetType = "unknown";
        private double eyeX, eyeY, eyeZ;
        private double boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ;
        private boolean creativeOrSpectator, attackerInVehicle, targetInVehicle;
        private int pingMillis;
        private long timestamp = System.currentTimeMillis();
        private String targetUniqueId = "";
        private float yaw, pitch;
        private Double attackRangeMax;
        private String attackRangeSource = "unknown";
        private long previousAttackAtMillis = -1L;
        private double targetVelX, targetVelY, targetVelZ;
        private boolean lagCompensationEnabled;
        private double lagCompensationMaxBlocks = 1.0D;

        public Builder attacker(java.util.UUID attacker) { this.attacker = attacker; return this; }
        public Builder targetType(String targetType) { this.targetType = targetType; return this; }
        public Builder eye(double x, double y, double z) { this.eyeX = x; this.eyeY = y; this.eyeZ = z; return this; }
        public Builder box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ) {
            this.boxMinX = minX; this.boxMinY = minY; this.boxMinZ = minZ;
            this.boxMaxX = maxX; this.boxMaxY = maxY; this.boxMaxZ = maxZ;
            return this;
        }
        public Builder creativeOrSpectator(boolean creativeOrSpectator) { this.creativeOrSpectator = creativeOrSpectator; return this; }
        public Builder attackerInVehicle(boolean attackerInVehicle) { this.attackerInVehicle = attackerInVehicle; return this; }
        public Builder targetInVehicle(boolean targetInVehicle) { this.targetInVehicle = targetInVehicle; return this; }
        public Builder pingMillis(int pingMillis) { this.pingMillis = pingMillis; return this; }
        public Builder timestamp(long timestamp) { this.timestamp = timestamp; return this; }
        public Builder targetUniqueId(String targetUniqueId) { this.targetUniqueId = targetUniqueId; return this; }
        public Builder yaw(float yaw) { this.yaw = yaw; return this; }
        public Builder pitch(float pitch) { this.pitch = pitch; return this; }
        /** Item-specific interaction range (null = unknown); source defaults to {@code unknown}. */
        public Builder attackRangeMax(Double attackRangeMax) { this.attackRangeMax = attackRangeMax; return this; }
        public Builder attackRangeSource(String attackRangeSource) {
            this.attackRangeSource = attackRangeSource == null ? "unknown" : attackRangeSource;
            return this;
        }
        public Builder previousAttackAtMillis(long previousAttackAtMillis) { this.previousAttackAtMillis = previousAttackAtMillis; return this; }
        public Builder targetVelocity(double x, double y, double z) { this.targetVelX = x; this.targetVelY = y; this.targetVelZ = z; return this; }
        public Builder lagCompensationEnabled(boolean lagCompensationEnabled) { this.lagCompensationEnabled = lagCompensationEnabled; return this; }
        public Builder lagCompensationMaxBlocks(double lagCompensationMaxBlocks) { this.lagCompensationMaxBlocks = lagCompensationMaxBlocks; return this; }

        public AttackContext build() {
            if (attacker == null) {
                attacker = new java.util.UUID(0L, 0L);
            }
            return new AttackContext(this);
        }
    }
}
