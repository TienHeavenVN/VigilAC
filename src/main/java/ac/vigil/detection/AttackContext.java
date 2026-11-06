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

    /**
     * Distance from the attacker's eye position to the closest point on the
     * target's axis-aligned bounding box. Implemented in doubles so it is
     * testable without a server.
     */
    public double distanceEyeToBox() {
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

        public AttackContext build() {
            if (attacker == null) {
                attacker = new java.util.UUID(0L, 0L);
            }
            return new AttackContext(this);
        }
    }
}
