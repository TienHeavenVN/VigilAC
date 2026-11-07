package ac.vigil.data.tracking;

import java.util.ArrayDeque;
import java.util.List;
import java.util.ArrayList;

/**
 * Bounded rolling window of the client's reported look direction, fed from
 * the packet telemetry path (rotation-only and position+rotation packets).
 * Used by combat analysis for pre-attack rotation deltas, angular velocity
 * and aim-correction patterns.
 *
 * <p>Vanilla sends at most ~20 packets/s, so a 2 s / 40-sample window holds
 * two seconds of history with margin. Synchronized, O(1) writes.</p>
 */
public final class RotationTracker {

    public static final int CAPACITY = 64;
    public static final long WINDOW_MILLIS = 2_000L;

    public static final class Sample {
        public final long time;
        public final float yaw;
        public final float pitch;

        Sample(long time, float yaw, float pitch) {
            this.time = time;
            this.yaw = yaw;
            this.pitch = pitch;
        }
    }

    private final ArrayDeque<Sample> samples = new ArrayDeque<>();

    public synchronized void sample(long now, float yaw, float pitch) {
        evict(now);
        samples.addLast(new Sample(now, yaw, pitch));
        if (samples.size() > CAPACITY) {
            samples.pollFirst();
        }
    }

    /** Most recent sample, or {@code null} when none within the window. */
    public synchronized Sample latest(long now) {
        evict(now);
        return samples.peekLast();
    }

    /** Copy of the current window (oldest first). */
    public synchronized List<Sample> window(long now) {
        evict(now);
        return new ArrayList<>(samples);
    }

    /**
     * Total absolute angular distance covered by the direction stream over
     * the trailing {@code recentMillis} — a cheap proxy for "tracking" vs
     * "fixated" aim. 0 when fewer than two samples exist.
     */
    public synchronized double angularDistanceCovered(long now, long recentMillis) {
        evict(now);
        long from = now - recentMillis;
        double sum = 0.0D;
        Sample prev = null;
        for (Sample s : samples) {
            if (s.time < from) {
                continue;
            }
            if (prev != null) {
                double yd = shortestAngleDegrees(prev.yaw, s.yaw);
                double pd = Math.abs(prev.pitch - s.pitch);
                sum += Math.hypot(yd, pd);
            }
            prev = s;
        }
        return sum;
    }

    /** Latest recorded (yaw, pitch) sample, or null when none within the window. */
    public synchronized float[] latestSample() {
        evict(System.currentTimeMillis());
        Sample latest = samples.peekLast();
        if (latest == null) {
            return null;
        }
        return new float[]{ latest.yaw, latest.pitch };
    }

    /** Angle between the latest two samples (degrees), or 0 when unknown. */
    public synchronized double lastDelta(long now) {
        evict(now);
        if (samples.size() < 2) {
            return 0.0D;
        }
        Sample first = null, second = null;
        for (Sample s : samples) {
            first = second;
            second = s;
        }
        double yd = shortestAngleDegrees(first.yaw, second.yaw);
        double pd = Math.abs(first.pitch - second.pitch);
        return Math.hypot(yd, pd);
    }

    public synchronized int size(long now) {
        evict(now);
        return samples.size();
    }

    public synchronized void clear() {
        samples.clear();
    }

    private void evict(long now) {
        long cutoff = now - WINDOW_MILLIS;
        while (!samples.isEmpty() && samples.peekFirst().time < cutoff) {
            samples.pollFirst();
        }
    }

    /** Smallest absolute angular distance between two yaw values (0-180). */
    public static double shortestAngleDegrees(float from, float to) {
        double diff = (to - from) % 360.0D;
        if (diff > 180.0D) {
            diff -= 360.0D;
        } else if (diff < -180.0D) {
            diff += 360.0D;
        }
        return Math.abs(diff);
    }
}
