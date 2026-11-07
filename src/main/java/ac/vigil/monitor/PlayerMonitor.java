package ac.vigil.monitor;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Unified per-player continuous monitoring state.
 *
 * <h2>What it maintains</h2>
 * <ul>
 *   <li>Latest continuous state: position, yaw/pitch, ground flag,
 *       sprint/sneak/glide/swim/vehicle flags, potion levels, game mode,
 *       world, ping (EMA) and velocity (horizontal/vertical EMAs).</li>
 *   <li>Ground-transition facts with timestamps ({@code landed}, {@code airborne-since}).</li>
 *   <li>Damage separated by cause — FALL vs everything else. Unrelated damage
 *       is recorded but is <b>never</b> exculpatory for NoFall.</li>
 *   <li>A bounded {@link EvidenceStore} of discrete observations (landings,
 *       impulses, teleports, attacks, block actions, check evidence).</li>
 *   <li>Per-check detection status inputs (evidence count, last detection,
 *       confidence EMA, latest exemption reason) — see {@link #status}.</li>
 * </ul>
 *
 * <h2>Threading</h2>
 * All public methods are synchronized and O(1) (transitions and detections are
 * the only paths that allocate). Callers: main thread (listeners), the decay
 * task (status/VL reads) and the command thread (monitor rendering).
 *
 * <h2>Bounds</h2>
 * The evidence ring holds at most {@code EvidenceStore.DEFAULT_CAPACITY}
 * entries with a 90 s TTL; the state block is a fixed set of fields; the
 * status map has one entry per registered check. No unbounded collections
 * exist here, and the owning {@code PlayerData} is discarded on quit.
 */
public final class PlayerMonitor {

    public static final long EXEMPTION_FRESHNESS_MILLIS = 5_000L;

    private final LongSupplier clock;
    private final EvidenceStore evidence;

    // Continuous state (fixed-size, overwritten in place)
    private double lastX, lastY, lastZ;
    private float lastYaw, lastPitch;
    private boolean onGround = true;
    private boolean sprinting, sneaking, gliding, swimming, inVehicle, levitation, slowFalling;
    private int speedPotionLevel;
    private double pingEma;
    private double velocityHorizontalEma;
    private double velocityVerticalEma;
    private String worldName = "";
    private String gameMode = "";
    private long lastMoveAt;
    private long lastLandingAt;
    private double lastLandingDescent;
    private long lastTeleportAt;
    private long lastVelocityAt;
    private long lastWorldChangeAt;
    private long lastFallDamageAt;
    private long lastGenericDamageAt;
    private long totalObservations;

    // Per-check status inputs (one entry per check that ever recorded)
    private final Map<String, CheckStatus> statuses = new HashMap<>();

    public PlayerMonitor(LongSupplier clock) {
        this(clock, new EvidenceStore(EvidenceStore.DEFAULT_CAPACITY, EvidenceStore.DEFAULT_TTL_MILLIS, clock));
    }

    /** Test-visible constructor with an explicit evidence store. */
    public PlayerMonitor(LongSupplier clock, EvidenceStore evidence) {
        this.clock = clock;
        this.evidence = evidence;
    }

    public long nowMillis() {
        return clock.getAsLong();
    }

    // ---------------------------------------------------------------- moves

    /** Records one movement observation (from the main move listener). */
    public synchronized void noteMove(MoveSample sample) {
        totalObservations++;
        long now = sample.timestamp();

        if (!sample.worldName().isEmpty() && !worldName.isEmpty() && !worldName.equals(sample.worldName())) {
            lastWorldChangeAt = now;
            evidence.add(now, EvidenceType.WORLD_CHANGE, 1.0D, Evidence.SOURCE_SERVER,
                    worldName + " -> " + sample.worldName());
        }
        worldName = sample.worldName();
        gameMode = sample.gameMode();

        // Ground transitions (client-reported flag is an untrusted observation;
        // recorded as such — checks decide how much to trust it)
        if (sample.onGround() != onGround) {
            if (sample.onGround()) {
                lastLandingAt = now;
                evidence.add(now, EvidenceType.LANDED, 1.0D, Evidence.SOURCE_CLIENT, "air -> ground");
            } else {
                evidence.add(now, EvidenceType.GROUND_EXIT, 1.0D, Evidence.SOURCE_CLIENT, "ground -> air");
            }
            onGround = sample.onGround();
        }

        // Velocity EMAs (skip implausible dt; dt==0 carries no motion rate)
        long dt = now - lastMoveAt;
        if (dt > 0L && dt <= 1_000L && lastMoveAt != 0L) {
            double dx = sample.x() - lastX;
            double dy = sample.y() - lastY;
            double dz = sample.z() - lastZ;
            double horizontal = Math.sqrt(dx * dx + dz * dz) / (dt / 1000.0D);
            double vertical = dy / (dt / 1000.0D);
            velocityHorizontalEma = velocityHorizontalEma * 0.6D + horizontal * 0.4D;
            velocityVerticalEma = velocityVerticalEma * 0.6D + vertical * 0.4D;
        }
        lastMoveAt = now;

        // Ping EMA
        pingEma = pingEma == 0.0D ? sample.pingMillis() : pingEma * 0.7D + sample.pingMillis() * 0.3D;

        lastX = sample.x();
        lastY = sample.y();
        lastZ = sample.z();
        lastYaw = sample.yaw();
        lastPitch = sample.pitch();
        sprinting = sample.sprinting();
        sneaking = sample.sneaking();
        gliding = sample.gliding();
        swimming = sample.swimming();
        inVehicle = sample.inVehicle();
        levitation = sample.levitation();
        slowFalling = sample.slowFalling();
        speedPotionLevel = sample.speedPotionLevel();
    }

    // ------------------------------------------------------- server events

    /** Records a server-side teleport. */
    public synchronized void noteTeleport(String detail) {
        long now = clock.getAsLong();
        lastTeleportAt = now;
        evidence.add(now, EvidenceType.TELEPORT, 1.0D, Evidence.SOURCE_SERVER, detail == null ? "" : detail);
    }

    /** Records a server-side knockback impulse (PlayerVelocityEvent). */
    public synchronized void noteVelocity(double speed) {
        long now = clock.getAsLong();
        lastVelocityAt = now;
        evidence.add(now, EvidenceType.VELOCITY_IMPULSE, 1.0D, Evidence.SOURCE_SERVER,
                String.format(java.util.Locale.US, "speed=%.3f", speed));
    }

    /** Records an EntityDamageEvent, separated by cause (timestamp from the injected clock). */
    public synchronized void noteDamage(boolean fall) {
        noteDamage(fall, clock.getAsLong());
    }

    /** Records an EntityDamageEvent at an explicit timestamp (keeps tests deterministic). */
    public synchronized void noteDamage(boolean fall, long timestamp) {
        long now = timestamp;
        if (fall) {
            lastFallDamageAt = now;
            evidence.add(now, EvidenceType.DAMAGE_FALL, 1.0D, Evidence.SOURCE_SERVER, "cause=FALL");
        } else {
            lastGenericDamageAt = now;
            evidence.add(now, EvidenceType.DAMAGE_GENERIC, 1.0D, Evidence.SOURCE_SERVER, "cause!=FALL");
        }
    }

    /** Records a melee attack observation (damage event keyed). */
    public synchronized void noteAttack(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.ATTACK_MELEE, 1.0D, Evidence.SOURCE_SERVER,
                detail == null ? "" : detail);
    }

    /** Records a completed block break. */
    public synchronized void noteBreak(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.BREAK_BLOCK, 1.0D, Evidence.SOURCE_SERVER,
                detail == null ? "" : detail);
    }

    /** Records a suspected impossible break (below the vanilla lower bound). */
    public synchronized void noteImpossibleBreak(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.FAST_BREAK_IMPOSSIBLE, 1.0D, Evidence.SOURCE_MONITOR,
                detail == null ? "" : detail);
    }

    /** Records a completed block placement. */
    public synchronized void notePlace(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.PLACE_BLOCK, 1.0D, Evidence.SOURCE_SERVER,
                detail == null ? "" : detail);
    }

    // ------------------------------------------------- landing / NoFall

    /** Records a landing with accumulated descent; call from the movement side that knows the geometry. */
    public synchronized void noteLanding(double descent) {
        lastLandingAt = clock.getAsLong();
        lastLandingDescent = descent;
    }

    /** Records a landing that survived NoFall corroboration (descent ≥ threshold, no fall damage). */
    public synchronized void noteNoFallSuspiciousLanding(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.NOFALL_SUSPICIOUS_LANDING, 0.8D, Evidence.SOURCE_MONITOR,
                detail == null ? "" : detail);
    }

    /** Records generic check evidence (call alongside a VL callback, or vl == 0 diagnostics). */
    public synchronized void noteCheckEvidence(String check, String detail) {
        long now = clock.getAsLong();
        evidence.add(new Evidence(now, EvidenceType.CHECK_EVIDENCE, 1.0D, Evidence.SOURCE_MONITOR,
                detail == null ? "" : detail, check == null ? "" : check));
    }

    /** Records an inventory click observation (view type in detail). */
    public synchronized void noteInventoryClick(String detail) {
        evidence.add(clock.getAsLong(), EvidenceType.INVENTORY_CLICK, 1.0D, Evidence.SOURCE_SERVER,
                detail == null ? "" : detail);
    }

    // --------------------------------------------------- detection status

    /**
     * Notes that check {@code check} produced {@code delta > 0} VL. Updates the
     * evidence count, the last-detection timestamp and the confidence EMA
     * (toward {@code min(1, vl / alertVl)}).
     */
    public synchronized void noteViolation(String check, double vl, double alertVl) {
        CheckStatus st = statuses.computeIfAbsent(check, k -> new CheckStatus());
        st.evidenceCount++;
        st.lastDetectionAt = clock.getAsLong();
        double target = alertVl > 0.0D ? Math.min(1.0D, Math.max(0.0D, vl / alertVl)) : 1.0D;
        st.confidence = st.confidence + 0.5D * (target - st.confidence);
    }

    /** Notes that check {@code check} abstained for an environmental reason. */
    public synchronized void noteExemption(String check, String reason) {
        CheckStatus st = statuses.computeIfAbsent(check, k -> new CheckStatus());
        st.exemptionReason = reason == null ? "" : reason;
        st.exemptionAt = clock.getAsLong();
    }

    /**
     * Builds the current status view for one check. The caller supplies the
     * live VL and the check's configuration so state is never stale.
     *
     * @param check   check name
     * @param vl      live violation level from the VL store
     * @param alertVl configured alert threshold
     * @param enabled whether the check is enabled in config
     */
    public synchronized DetectionStatus status(String check, double vl, double alertVl, boolean enabled) {
        CheckStatus st = statuses.get(check);
        long evidenceCount = st == null ? 0L : st.evidenceCount;
        long lastDetectionAt = st == null ? 0L : st.lastDetectionAt;
        double confidence = st == null ? 0.0D : st.confidence;
        String exemptionReason = st == null ? "" : st.exemptionReason;
        long exemptionAt = st == null ? 0L : st.exemptionAt;

        // Per-check evidence history: scan the bounded ring once (O(capacity)).
        long lastEvidenceAt = 0L;
        String lastEvidenceDetail = "";
        for (Evidence e : evidence.snapshot()) {
            if (e.type() == EvidenceType.CHECK_EVIDENCE && e.check().equals(check)) {
                lastEvidenceAt = e.timeMillis();
                lastEvidenceDetail = e.detail();
            }
        }

        DetectionState state;
        if (!enabled) {
            state = DetectionState.DISABLED;
        } else if (!exemptionReason.isEmpty()
                && clock.getAsLong() - exemptionAt <= EXEMPTION_FRESHNESS_MILLIS && vl <= 0.0D) {
            state = DetectionState.EXEMPT;
        } else if (alertVl > 0.0D && vl >= alertVl) {
            state = DetectionState.VIOLATION;
        } else if (vl > 0.0D) {
            state = DetectionState.SUSPECT;
        } else {
            state = DetectionState.CLEAR;
        }
        return new DetectionStatus(check, state, vl, confidence, lastDetectionAt, evidenceCount,
                exemptionReason, exemptionAt, lastEvidenceAt, lastEvidenceDetail);
    }

    // ------------------------------------------------------------ queries

    /** Age of the newest evidence of {@code type} in ms, or -1 if none on record. */
    public synchronized long ageOfLatest(EvidenceType type) {
        Evidence latest = evidence.latestOfType(type);
        return latest == null ? -1L : Math.max(0L, clock.getAsLong() - latest.timeMillis());
    }

    /** Count of evidence of {@code type} at/after {@code sinceMillis} (TTL-bounded). */
    public synchronized int countEvidenceSince(EvidenceType type, long sinceMillis) {
        return evidence.countByTypeSince(type, sinceMillis);
    }

    /** Chronological snapshot of unexpired evidence (bounded by store capacity). */
    public synchronized List<Evidence> evidenceSnapshot() {
        return evidence.snapshot();
    }

    public synchronized int evidenceCount() {
        return evidence.size();
    }

    public synchronized long totalObservations() {
        return totalObservations;
    }

    // Latest continuous state (for /vigilac monitor)
    public synchronized double lastX() { return lastX; }
    public synchronized double lastY() { return lastY; }
    public synchronized double lastZ() { return lastZ; }
    public synchronized float lastYaw() { return lastYaw; }
    public synchronized float lastPitch() { return lastPitch; }
    public synchronized boolean isOnGround() { return onGround; }
    public synchronized boolean isSprinting() { return sprinting; }
    public synchronized boolean isSneaking() { return sneaking; }
    public synchronized boolean isGliding() { return gliding; }
    public synchronized boolean isSwimming() { return swimming; }
    public synchronized boolean isInVehicle() { return inVehicle; }
    public synchronized boolean isLevitation() { return levitation; }
    public synchronized boolean isSlowFalling() { return slowFalling; }
    public synchronized int speedPotionLevel() { return speedPotionLevel; }
    public synchronized double pingEma() { return pingEma; }
    public synchronized double velocityHorizontalEma() { return velocityHorizontalEma; }
    public synchronized double velocityVerticalEma() { return velocityVerticalEma; }
    public synchronized String worldName() { return worldName; }
    public synchronized String gameMode() { return gameMode; }
    public synchronized long lastLandingAt() { return lastLandingAt; }
    public synchronized double lastLandingDescent() { return lastLandingDescent; }
    public synchronized long lastTeleportAt() { return lastTeleportAt; }
    public synchronized long lastVelocityAt() { return lastVelocityAt; }
    public synchronized long lastWorldChangeAt() { return lastWorldChangeAt; }
    public synchronized long lastFallDamageAt() { return lastFallDamageAt; }
    public synchronized long lastGenericDamageAt() { return lastGenericDamageAt; }

    private static final class CheckStatus {
        long evidenceCount;
        long lastDetectionAt;
        double confidence;
        String exemptionReason = "";
        long exemptionAt;
    }
}
