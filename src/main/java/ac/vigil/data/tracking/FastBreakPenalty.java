package ac.vigil.data.tracking;

/**
 * Decaying penalty bucket for missing break time, inspired by legacy
 * anti-cheat penalty models (penalty-bucket accumulators, reimplemented).
 *
 * <p>Fast breaks arrive in bursts that single-event filters cannot
 * distinguish from efficiency-footrange luck; a decaying bucket converts a
 * cluster of impossible digs into sustained evidence while letting a one-off
 * expiry pass silently.</p>
 *
 * <p>Score halves every {@code halfLifeMillis}; {@code addMissing} credits
 * milliseconds of missing dig time. The check flags only when the score
 * exceeds a grace period — never on the bucket alone for a single fast
 * block. Fixed-size O(1) state.</p>
 */
public final class FastBreakPenalty {

    public static final double DEFAULT_HALF_LIFE_MILLIS = 3_000.0D;

    private double bucketMillis;
    private long lastUpdateAt; // 0 when empty
    private double halfLifeMillis = DEFAULT_HALF_LIFE_MILLIS;

    /** Adds {@code missingMillis} of unexplained dig-time deficit observed at {@code now}. */
    public synchronized void addMissing(double missingMillis, long now) {
        if (!(missingMillis > 0.0D) || Double.isNaN(missingMillis) || Double.isInfinite(missingMillis)) {
            decayTo(now);
            return;
        }
        decayTo(now);
        bucketMillis += missingMillis;
        lastUpdateAt = now;
    }

    /** Current (temporally decayed) score in milliseconds of missing dig time. */
    public synchronized double score(long now) {
        decayTo(now);
        return bucketMillis;
    }

    /** Halves the bucket after a flagged exceed so one flag decays, it does not latch. */
    public synchronized void dampen() {
        bucketMillis *= 0.5D;
    }

    public synchronized void reset() {
        bucketMillis = 0.0D;
        lastUpdateAt = 0L;
    }

    /** For tests and configuration. */
    public synchronized void setHalfLifeMillis(double halfLifeMillis) {
        if (halfLifeMillis > 0.0D && !Double.isNaN(halfLifeMillis)) {
            this.halfLifeMillis = halfLifeMillis;
        }
    }

    private void decayTo(long now) {
        if (lastUpdateAt == 0L) {
            lastUpdateAt = now;
            return;
        }
        long elapsed = now - lastUpdateAt;
        if (elapsed <= 0L) {
            return;
        }
        bucketMillis *= Math.pow(0.5D, elapsed / halfLifeMillis);
        if (bucketMillis < 1.0D) {
            bucketMillis = 0.0D;
        }
        lastUpdateAt = now;
    }
}
