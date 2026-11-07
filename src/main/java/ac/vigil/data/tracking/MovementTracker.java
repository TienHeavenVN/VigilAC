package ac.vigil.data.tracking;

/**
 * Per-player movement evidence accumulator, owned by {@code PlayerData}.
 * Pure Java (no Bukkit) so every path is unit-testable. All public methods
 * are synchronized; callers are the main thread (move events) and the decay
 * task (read-only paths).
 *
 * <p>Responsibilities:</p>
 * <ul>
 *   <li>Airborne bookkeeping (fly/hover evidence, fall-distance estimation)
 *       using the move-event stream only — no packets required.</li>
 *   <li>Movement-packet rate sampling for the timer check. Two sources are
 *       tracked: the Bukkit move-event stream (always available, coalesced by
 *       the server to at most ~20/s on Paper) and a raw client-packet ring
 *       fed by the optional packetevents integration (true packet rate,
 *       pre-coalescing). The listener uses the maximum of both.</li>
 * </ul>
 */
public final class MovementTracker {

    /** Timestamp ring for movement packets (covers any rate under ~2.5/s sustained counting window precision). */
    private static final int MOVE_RING_SIZE = 256;

    private long airborneSince;          // 0 when grounded
    private double netVerticalAirborne;  // signed accumulated dy while airborne
    private int upwardAirborneTicks;     // consecutive positive-dy ticks while airborne
    private double descentAirborne;      // accumulated downward distance while airborne
    private int airborneTicks;           // total airborne observations since becoming airborne
    private int suspiciousLandingStreak; // consecutive landings with excessive fall + no damage

    private long timerExcessSince;       // 0 when within limits

    private final long[] moveRing = new long[MOVE_RING_SIZE];
    private int moveRingIndex;
    private int moveRingCount;

    /** Raw position/rotation packet timestamps from packetevents (any thread). */
    private final long[] rawRing = new long[MOVE_RING_SIZE];
    private int rawRingIndex;
    private int rawRingCount;

    /** Marks the player as airborne; no-op if already airborne. */
    public synchronized void enterAirborne(long now) {
        if (airborneSince == 0L) {
            airborneSince = now;
            netVerticalAirborne = 0.0D;
            upwardAirborneTicks = 0;
            descentAirborne = 0.0D;
            airborneTicks = 0;
        }
    }

    /** Accumulates one airborne observation (signed dy). */
    public synchronized void accumulateAirborne(double dy) {
        if (airborneSince == 0L) {
            return;
        }
        netVerticalAirborne += dy;
        airborneTicks++;
        if (dy > 0.0D) {
            upwardAirborneTicks++;
        } else if (dy < 0.0D) {
            upwardAirborneTicks = 0;
            descentAirborne += -dy;
        }
    }

    /** Ends the airborne period and returns the total accumulated descent (blocks). */
    public synchronized double leaveAirborne() {
        double result = descentAirborne;
        airborneSince = 0L;
        netVerticalAirborne = 0.0D;
        upwardAirborneTicks = 0;
        descentAirborne = 0.0D;
        airborneTicks = 0;
        return result;
    }

    /** Milliseconds since the airborne period started, or 0 if grounded. */
    public synchronized long airborneMillis(long now) {
        return airborneSince == 0L ? 0L : Math.max(0L, now - airborneSince);
    }

    public synchronized boolean isAirborne() {
        return airborneSince != 0L;
    }

    /** Net signed vertical displacement since becoming airborne. */
    public synchronized double getNetVerticalAirborne() {
        return netVerticalAirborne;
    }

    /** Consecutive positive-dy ticks since becoming airborne (or since last downward/level tick). */
    public synchronized int getUpwardAirborneTicks() {
        return upwardAirborneTicks;
    }

    /** Accumulated downward distance since becoming airborne. */
    public synchronized double getDescentAirborne() {
        return descentAirborne;
    }

    /** Total airborne observations (move packets) since becoming airborne. */
    public synchronized int getAirborneTicks() {
        return airborneTicks;
    }

    public synchronized int addSuspiciousLanding() {
        return ++suspiciousLandingStreak;
    }

    public synchronized void resetSuspiciousLandings() {
        suspiciousLandingStreak = 0;
    }

    public synchronized int getSuspiciousLandingStreak() {
        return suspiciousLandingStreak;
    }

    /** Marks "currently above the timer threshold"; returns the start time of the excess streak (epoch ms). */
    public synchronized long markTimerExcess(long now) {
        if (timerExcessSince == 0L) {
            timerExcessSince = now;
        }
        return timerExcessSince;
    }

    public synchronized void resetTimerExcess() {
        timerExcessSince = 0L;
    }

    public synchronized long getTimerExcessSince() {
        return timerExcessSince;
    }

    /** Records one movement-packet event (position OR rotation) at {@code now}. */
    public synchronized void noteMovePacket(long now) {
        moveRing[moveRingIndex] = now;
        moveRingIndex = (moveRingIndex + 1) % MOVE_RING_SIZE;
        if (moveRingCount < MOVE_RING_SIZE) {
            moveRingCount++;
        }
    }

    /** Number of movement packets recorded in the trailing 1000 ms before {@code now}. */
    public synchronized double movePacketsPerSecond(long now) {
        return trailingRate(moveRing, moveRingIndex, moveRingCount, now);
    }

    /** Records one raw client movement packet (position/rotation) at {@code now}; safe from any thread. */
    public synchronized void noteRawMovePacket(long now) {
        rawRing[rawRingIndex] = now;
        rawRingIndex = (rawRingIndex + 1) % MOVE_RING_SIZE;
        if (rawRingCount < MOVE_RING_SIZE) {
            rawRingCount++;
        }
    }

    /** Number of raw client movement packets in the trailing 1000 ms before {@code now}. */
    public synchronized double rawMovePacketsPerSecond(long now) {
        return trailingRate(rawRing, rawRingIndex, rawRingCount, now);
    }

    private static double trailingRate(long[] ring, int index, int count, long now) {
        long cutoff = now - 1_000L;
        int found = 0;
        int idx = index - 1;
        for (int i = 0; i < count; i++) {
            int slot = idx - i;
            if (slot < 0) {
                slot += MOVE_RING_SIZE;
            }
            long ts = ring[slot];
            if (ts < cutoff) {
                break; // ring is chronological
            }
            found++;
        }
        return found;
    }
}
