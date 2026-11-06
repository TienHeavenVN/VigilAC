package ac.vigil.data;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player detection state. Thread-safe: callers may be the main thread
 * (move/attack events), a packet thread (packet counters) or the decay task.
 * VL maps and trackers use internal locking; hot counters are volatile.
 */
public final class PlayerData {

    private final UUID uuid;
    private final long joinTimeMillis;

    /** VL per check name. */
    private final Map<String, Double> violationLevels = new HashMap<>();
    /** Last time (epoch ms) VL was added per check, used for decay delay. */
    private final Map<String, Long> lastViolationAt = new HashMap<>();
    /** Last time (epoch ms) VL was added per check, used for decay delay. */
    private final Map<String, Long> firstViolationAt = new HashMap<>();

    private double speedEma;
    private int speedEmaSamples;
    private long lastMoveAt;
    private double excessSeconds;

    private int consecutiveOverReach;
    private final ArrayDeque<Double> reachHistory = new ArrayDeque<>();

    private long knockbackGraceUntil;
    private long teleportGraceUntil;

    private final java.util.concurrent.atomic.AtomicLong packetCount = new java.util.concurrent.atomic.AtomicLong();
    private long packetWindowStart = System.currentTimeMillis();
    private volatile double packetsPerSecond;

    private boolean debug;

    public PlayerData(UUID uuid) {
        this.uuid = uuid;
        this.joinTimeMillis = System.currentTimeMillis();
    }

    public UUID getUuid() {
        return uuid;
    }

    public long getJoinTimeMillis() {
        return joinTimeMillis;
    }

    /** Adds VL, capped at {@code maxVl}; returns the new VL. */
    public synchronized double addViolation(String checkName, double amount, double maxVl) {
        double current = violationLevels.getOrDefault(checkName, 0.0D);
        double next = Math.min(maxVl, current + amount);
        violationLevels.put(checkName, next);
        long now = System.currentTimeMillis();
        lastViolationAt.put(checkName, now);
        firstViolationAt.putIfAbsent(checkName, now);
        return next;
    }

    public synchronized double getViolationLevel(String checkName) {
        return violationLevels.getOrDefault(checkName, 0.0D);
    }

    public synchronized Map<String, Double> getViolationLevelsSnapshot() {
        return new HashMap<>(violationLevels);
    }

    public synchronized long getLastViolationAt(String checkName) {
        return lastViolationAt.getOrDefault(checkName, 0L);
    }

    /**
     * Decays VL for every check whose last violation is older than
     * {@code decayDelayMillis}. Returns true if any VL changed.
     */
    public synchronized boolean decay(double amountPerDecayTick, long decayDelayMillis) {
        long now = System.currentTimeMillis();
        boolean changed = false;
        for (Map.Entry<String, Double> entry : violationLevels.entrySet()) {
            Long last = lastViolationAt.get(entry.getKey());
            if (last == null || now - last < decayDelayMillis) {
                continue;
            }
            double current = entry.getValue();
            if (current > 0.0D) {
                entry.setValue(Math.max(0.0D, current - amountPerDecayTick));
                changed = true;
            }
        }
        return changed;
    }

    public synchronized void clearViolationLevel(String checkName) {
        violationLevels.remove(checkName);
        lastViolationAt.remove(checkName);
        firstViolationAt.remove(checkName);
    }

    /** Exponential moving average of horizontal speed (blocks/sec). */
    public synchronized double updateSpeedEma(double instantSpeed) {
        double alpha = 0.35D;
        speedEma = speedEmaSamples == 0 ? instantSpeed : speedEma + alpha * (instantSpeed - speedEma);
        speedEmaSamples = Math.min(speedEmaSamples + 1, 10_000);
        return speedEma;
    }

    public synchronized double getSpeedEma() {
        return speedEma;
    }

    public synchronized long getLastMoveAt() {
        return lastMoveAt;
    }

    public synchronized void setLastMoveAt(long lastMoveAt) {
        this.lastMoveAt = lastMoveAt;
    }

    /**
     * Accumulates time (seconds) spent above the configured cap. Pass a
     * negative value to drain the accumulator; the result never drops below 0.
     */
    public synchronized double addExcessTime(double seconds) {
        excessSeconds = Math.max(0.0D, excessSeconds + seconds);
        return excessSeconds;
    }

    public synchronized void resetExcessTime() {
        excessSeconds = 0.0D;
    }

    public synchronized double getExcessSeconds() {
        return excessSeconds;
    }

    public synchronized int addOverReachAttack() {
        return ++consecutiveOverReach;
    }

    public synchronized void resetOverReachCounter() {
        consecutiveOverReach = 0;
    }

    public synchronized int getConsecutiveOverReach() {
        return consecutiveOverReach;
    }

    /** Remembers the most recent N reach distances (for EMA / multi-observation analysis). */
    public synchronized void recordReach(double distance, int maxSamples) {
        reachHistory.addLast(distance);
        while (reachHistory.size() > maxSamples) {
            reachHistory.pollFirst();
        }
    }

    public synchronized double reachEma() {
        double sum = 0.0D;
        int count = 0;
        double weight = 1.0D;
        // Weight recent samples higher: linear weights from oldest..newest.
        double[] samples = new double[reachHistory.size()];
        int i = 0;
        for (double d : reachHistory) {
            samples[i++] = d;
        }
        for (double sample : samples) {
            sum += sample * weight;
            weight += 1.0D;
            count++;
        }
        double weightSum = (double) count * (count + 1) / 2.0D;
        return weightSum == 0.0D ? 0.0D : sum / weightSum;
    }

    public synchronized int getReachSampleCount() {
        return reachHistory.size();
    }

    public void setKnockbackGraceUntil(long epochMillis) {
        this.knockbackGraceUntil = Math.max(this.knockbackGraceUntil, epochMillis);
    }

    public long getKnockbackGraceUntil() {
        return knockbackGraceUntil;
    }

    public void setTeleportGraceUntil(long epochMillis) {
        this.teleportGraceUntil = Math.max(this.teleportGraceUntil, epochMillis);
    }

    public long getTeleportGraceUntil() {
        return teleportGraceUntil;
    }

    public boolean isKnockbackGrace() {
        return System.currentTimeMillis() < knockbackGraceUntil;
    }

    public boolean isTeleportGrace() {
        return System.currentTimeMillis() < teleportGraceUntil;
    }

    /** Thread-safe packet throughput counter fed by the packet listener (any thread). */
    public void notePacket() {
        long now = System.currentTimeMillis();
        long count = packetCount.incrementAndGet();
        long windowStart = packetWindowStart;
        long elapsed = now - windowStart;
        if (elapsed >= 1_000L) {
            packetsPerSecond = count * 1000.0D / (double) elapsed;
            packetWindowStart = now;
            packetCount.set(0);
        }
    }

    public double getPacketsPerSecond() {
        return packetsPerSecond;
    }

    public boolean isDebug() {
        return debug;
    }

    public void setDebug(boolean debug) {
        this.debug = debug;
    }
}
