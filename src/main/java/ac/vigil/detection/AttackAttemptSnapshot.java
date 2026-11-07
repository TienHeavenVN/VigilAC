package ac.vigil.detection;

/**
 * Immutable, netty-thread-safe snapshot of a melee attack attempt observed
 * at the packet layer. Contains primitives only; safe to enqueue and hand
 * off across threads. A snapshot alone is <b>not</b> evidence — it must be
 * resolved on the main thread (entity id → bounding box) before any reach
 * verdict exists.
 *
 * <p>{@code sequence} is a process-monotonic counter assigned at snapshot
 * creation. The dispatcher drains in FIFO order, so the sequence preserves
 * packet order across the hand-off.</p>
 */
public final class AttackAttemptSnapshot {

    private static final java.util.concurrent.atomic.AtomicLong SEQUENCE = new java.util.concurrent.atomic.AtomicLong();

    private final java.util.UUID attacker;
    private final int entityId;
    private final long timestamp;
    private final boolean hasRotation;
    private final float yaw;
    private final float pitch;
    private final String source;      // "ATTACK" or "INTERACT_ENTITY"
    private final long sequence;

    public AttackAttemptSnapshot(java.util.UUID attacker, int entityId, long timestamp,
                                 boolean hasRotation, float yaw, float pitch, String source) {
        this.attacker = attacker;
        this.entityId = entityId;
        this.timestamp = timestamp;
        this.hasRotation = hasRotation;
        this.yaw = yaw;
        this.pitch = pitch;
        this.source = source == null ? "UNKNOWN" : source;
        this.sequence = SEQUENCE.incrementAndGet();
    }

    /** Factory without rotation data (no packetevents rotation seen yet). */
    public static AttackAttemptSnapshot of(java.util.UUID attacker, int entityId, long timestamp, String source) {
        return new AttackAttemptSnapshot(attacker, entityId, timestamp, false, 0.0F, 0.0F, source);
    }

    public java.util.UUID getAttacker() { return attacker; }
    public int getEntityId() { return entityId; }
    public long getTimestamp() { return timestamp; }
    /** True when the attacker's latest packet-layer rotation was attached. */
    public boolean hasRotation() { return hasRotation; }
    /** Last known attacker yaw at attempt time (MC convention); meaningful only when {@link #hasRotation()}. */
    public float getYaw() { return yaw; }
    /** Last known attacker pitch at attempt time; meaningful only when {@link #hasRotation()}. */
    public float getPitch() { return pitch; }
    /** Packet source, e.g. {@code ATTACK} or {@code INTERACT_ENTITY}. */
    public String getSource() { return source; }
    public long getSequence() { return sequence; }
}
