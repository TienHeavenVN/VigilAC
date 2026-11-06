package ac.vigil.data.tracking;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Set;

/**
 * Per-player combat evidence accumulator, owned by {@code PlayerData}.
 * Pure Java (no Bukkit) so every path is unit-testable. All public methods
 * are synchronized; combat events fire on the main thread only.
 *
 * <p>Responsibilities: click-interval statistics (auto-clicker), attack rate
 * (CPS), target-switch history (kill aura) and aim-snap bookkeeping.</p>
 */
public final class CombatTracker {

    private static final int INTERVAL_WINDOW = 20;
    private static final int CPS_WINDOW = 40;
    private static final int TARGET_WINDOW = 32;

    private long lastAttackAt;
    private final double[] intervalRing = new double[INTERVAL_WINDOW];
    private int intervalIndex;
    private int intervalCount;
    private int roboticStreak;

    private final ArrayDeque<Long> attackTimes = new ArrayDeque<>();

    /** Ring of recently damaged entity ids with timestamps, for distinct-target analysis. */
    private static final class TargetHit {
        final String id;
        final long at;

        TargetHit(String id, long at) {
            this.id = id;
            this.at = at;
        }
    }

    private final ArrayDeque<TargetHit> recentTargets = new ArrayDeque<>();

    private float lastYaw;
    private float lastPitch;
    private boolean hasLastAim;
    private int aimSnapStreak;

    /** Records an attack at {@code now}; interval statistics update automatically. */
    public synchronized void recordAttack(long now) {
        if (lastAttackAt > 0L) {
            double interval = Math.max(0.0D, now - lastAttackAt);
            intervalRing[intervalIndex] = interval;
            intervalIndex = (intervalIndex + 1) % INTERVAL_WINDOW;
            if (intervalCount < INTERVAL_WINDOW) {
                intervalCount++;
            }
        }
        lastAttackAt = now;
        attackTimes.addLast(now);
        while (attackTimes.size() > CPS_WINDOW) {
            attackTimes.pollFirst();
        }
    }

    public synchronized long getLastAttackAt() {
        return lastAttackAt;
    }

    public synchronized int getIntervalSampleCount() {
        return intervalCount;
    }

    /** Mean attack interval over the stored window (ms). NaN when empty. */
    public synchronized double intervalMean() {
        if (intervalCount == 0) {
            return Double.NaN;
        }
        double sum = 0.0D;
        for (int i = 0; i < intervalCount; i++) {
            sum += intervalRing[i];
        }
        return sum / intervalCount;
    }

    /** Population stddev of attack intervals over the stored window (ms). NaN when empty. */
    public synchronized double intervalStddev() {
        if (intervalCount == 0) {
            return Double.NaN;
        }
        double mean = intervalMean();
        double sq = 0.0D;
        for (int i = 0; i < intervalCount; i++) {
            double d = intervalRing[i] - mean;
            sq += d * d;
        }
        return Math.sqrt(sq / intervalCount);
    }

    /**
     * Attacks per second over the trailing window. The rate is measured
     * against the actual elapsed span of the attacks in the window (capped
     * at {@code windowMillis}), not against the full window — a player who
     * started attacking 0.4 s ago is measured over 0.4 s, never inflated.
     */
    public synchronized double cps(long now, long windowMillis) {
        if (attackTimes.isEmpty() || windowMillis <= 0L) {
            return 0.0D;
        }
        long cutoff = now - windowMillis;
        long oldest = -1L;
        int count = 0;
        for (Long ts : attackTimes) {
            if (ts != null && ts >= cutoff) {
                if (oldest < 0L) {
                    oldest = ts;
                }
                count++;
            }
        }
        if (count <= 1) {
            return count; // a lone attack carries no rate information
        }
        double spanSeconds = Math.max(0.05D, Math.min(windowMillis, now - oldest) / 1_000.0D);
        return count / spanSeconds;
    }

    public synchronized int addRoboticStreak() {
        return ++roboticStreak;
    }

    public synchronized void resetRoboticStreak() {
        roboticStreak = 0;
    }

    public synchronized int getRoboticStreak() {
        return roboticStreak;
    }

    /** Records damage dealt to {@code targetId} at {@code now}; keeps only entries within {@code windowMillis}. */
    public synchronized int recordTarget(String targetId, long now, int windowMillis) {
        String id = targetId == null ? "" : targetId;
        if (!id.isEmpty()) {
            recentTargets.addLast(new TargetHit(id, now));
        }
        long cutoff = now - windowMillis;
        while (!recentTargets.isEmpty() && recentTargets.peekFirst().at < cutoff) {
            recentTargets.pollFirst();
        }
        while (recentTargets.size() > TARGET_WINDOW) {
            recentTargets.pollFirst();
        }
        Set<String> distinct = new HashSet<>();
        for (TargetHit hit : recentTargets) {
            distinct.add(hit.id);
        }
        return distinct.size();
    }

    /** Distinct targets hit within the trailing window ending at {@code now}. */
    public synchronized int distinctTargets(long now, int windowMillis) {
        long cutoff = now - windowMillis;
        Set<String> distinct = new HashSet<>();
        for (TargetHit hit : recentTargets) {
            if (hit.at >= cutoff) {
                distinct.add(hit.id);
            }
        }
        return distinct.size();
    }

    /** Returns true when a previous aim snapshot exists; exposes it via the getters. */
    public synchronized boolean hasLastAim() {
        return hasLastAim;
    }

    public synchronized float getLastYaw() {
        return lastYaw;
    }

    public synchronized float getLastPitch() {
        return lastPitch;
    }

    public synchronized long lastAttackAgeMillis(long now) {
        return lastAttackAt <= 0L ? Long.MAX_VALUE : Math.max(0L, now - lastAttackAt);
    }

    /** Replaces the aim snapshot; call AFTER reading the previous one. */
    public synchronized void recordAim(float yaw, float pitch) {
        this.lastYaw = yaw;
        this.lastPitch = pitch;
        this.hasLastAim = true;
    }

    public synchronized int addAimSnapStreak() {
        return ++aimSnapStreak;
    }

    public synchronized void resetAimSnapStreak() {
        aimSnapStreak = 0;
    }

    public synchronized int getAimSnapStreak() {
        return aimSnapStreak;
    }
}
