package ac.vigil.data.tracking;

import java.util.ArrayDeque;

/**
 * Bounded state machine recognizing plausible slime-bounce arcs, so the Step
 * check can skip impossibility bookkeeping for a genuine bounce without
 * blanket-exempting slime (there is no global slime exemption) and without
 * ever clearing real step evidence.
 *
 * <h2>State machine</h2>
 * {@code IDLE -> RISING} on a tick that looks like a launch:
 * previous vertical velocity &lt; -0.05 (was falling), current rise
 * &gt; 0.5 and &le; {@value #LAUNCH_CAP}, and slime-family contact either
 * at the feet this tick or under the feet at either end of the tick (the
 * sneak-charge case, where the feet have already left the block).
 *
 * {@code RISING} tracks the expected ballistic decay
 * {@code expected(k) = launchV - 0.08*k} for (risingTicks+1)-th observation.
 * The arc ends (state returns to IDLE) when any of these hold:
 * <ul>
 *   <li>the observed deltaY deviates from the expectation by more than
 *       {@value #EXPECTED_DEVIATION} blocks;</li>
 *   <li>the rise <em>accelerates</em> by more than {@value #EXPECTED_DEVIATION}
 *       over the previous tick (impossible for a real bounce);</li>
 *   <li>{@value #MAX_RISING_TICKS} rising ticks elapsed, 1.5 s wall time
 *       elapsed since launch, or the arc's accumulated rise exceeded
 *       {@value #MAX_ARC_DISPLACEMENT} blocks (bounded by what a launch at
 *       the capture cap can reach);</li>
 *   <li>the rise reaches the apex ({@code deltaY <= 0}).</li>
 * </ul>
 *
 * <h2>Hard bounds (never unbounded grace)</h2>
 * <ul>
 *   <li>At most {@value #MAX_SEQUENCES_PER_WINDOW} bounce sequences per
 *       {@value #WINDOW_MILLIS} ms; beyond that a launch candidate opens no
 *       sequence and Step proceeds untouched.</li>
 *   <li>A full reset (no stale state survives) happens on teleports and when
 *       server TPS &lt; {@value #MIN_TPS} (the ballistic model breaks when
 *       ticks are not 50 ms).</li>
 *   <li>Only Step <em>impossibility accumulation</em> is suspended inside a
 *       validated arc. Every other check evaluates the player normally.</li>
 * </ul>
 *
 * <p>The tracker never flags anything; it answers {@link Verdict} per tick.
 * When evidence does not fit the ballistic model, the verdict is
 * {@link Verdict#NOT_BOUNCE} and the Step check applies unchanged.</p>
 */
public final class StepBounceTracker {

    public enum Verdict {
        /** Tick fits a validated slime-bounce arc; Step must not accumulate. */
        EXEMPT_BOUNCE,
        /** Not a plausible bounce (or arc ended/rate-limited); Step applies unchanged. */
        NOT_BOUNCE
    }

    private enum State {
        IDLE,
        RISING
    }

    private static final double GRAVITY = 0.08D;
    /** Sneak-charged full-height rebound may launch as high as ~1.6 blocks/tick (documented vanilla ceiling); above that it is Step, not bounce. */
    private static final double LAUNCH_CAP = 1.6D;
    /** Rounding margin so a delta reconstructed as (from + d) - from never flips the launch-cap comparison. */
    private static final double EPSILON = 1.0E-6D;
    private static final double LAUNCH_MIN = 0.5D;
    private static final double FALLING_BEFORE_LAUNCH = -0.05D;
    /** Tolerance on the expected ballistic decay, including float jitter and knockback-free arcs. */
    private static final double EXPECTED_DEVIATION = 0.22D;
    private static final int MAX_RISING_TICKS = 5;
    private static final long MAX_RISING_MILLIS = 1_500L;
    private static final int MAX_SEQUENCES_PER_WINDOW = 8;
    private static final long WINDOW_MILLIS = 4_000L;
    private static final double MAX_ARC_DISPLACEMENT = 16.0D;
    public static final double MIN_TPS = 10.0D;

    private State state = State.IDLE;
    private final ArrayDeque<Long> recentLaunches = new ArrayDeque<>();
    private double launchVelocity;
    private double previousRisingDeltaY;
    private double arcDisplacement;
    private int risingTicks;
    private long launchedAtMillis;
    private double previousDeltaY;
    private long sequenceCounter;

    /**
     * Feeds one movement observation.
     *
     * @param now               epoch millis
     * @param deltaY            vertical distance this tick
     * @param belowFeetSlippery slime/ice/honey-family block under the feet at either end of the tick
     * @param slipperyFeet      slime/ice/honey-family block AT the feet this tick
     * @param serverTps         current server TPS (ballistic model breaks below {@value #MIN_TPS})
     * @param justTeleported    a teleport/grace was observed; state must not survive it
     */
    public synchronized Verdict update(long now, double deltaY, boolean belowFeetSlippery,
                                       boolean slipperyFeet, double serverTps, boolean justTeleported) {
        evict(now);

        if (justTeleported || serverTps < MIN_TPS || Double.isNaN(serverTps)) {
            resetState();
            previousDeltaY = deltaY;
            return Verdict.NOT_BOUNCE;
        }

        if (state == State.IDLE) {
            boolean launchCandidate = (belowFeetSlippery || slipperyFeet)
                    && previousDeltaY < FALLING_BEFORE_LAUNCH
                    && deltaY > LAUNCH_MIN
                    && deltaY <= LAUNCH_CAP + EPSILON;
            if (launchCandidate && tryStartSequence(now, deltaY)) {
                acceptRisingTick(deltaY, true);
                return Verdict.EXEMPT_BOUNCE;
            }
            previousDeltaY = deltaY;
            return Verdict.NOT_BOUNCE;
        }

        // RISING
        risingTicks++;
        if (risingTicks > MAX_RISING_TICKS
                || now - launchedAtMillis > MAX_RISING_MILLIS
                || arcDisplacement + Math.abs(deltaY) > MAX_ARC_DISPLACEMENT) {
            endSequence();
            previousDeltaY = deltaY;
            return Verdict.NOT_BOUNCE;
        }

        double expected = launchVelocity - GRAVITY * risingTicks;
        boolean deviationOk = Math.abs(deltaY - expected) <= EXPECTED_DEVIATION;
        boolean accelerating = deltaY > previousRisingDeltaY + EXPECTED_DEVIATION;
        if (!deviationOk || accelerating) {
            endSequence();
            previousDeltaY = deltaY;
            return Verdict.NOT_BOUNCE;
        }

        acceptRisingTick(deltaY, false);
        previousDeltaY = deltaY;
        if (deltaY <= 0.0D || risingTicks >= MAX_RISING_TICKS) {
            endSequence();
        }
        return Verdict.EXEMPT_BOUNCE;
    }

    private boolean tryStartSequence(long now, double deltaY) {
        evict(now);
        if (recentLaunches.size() >= MAX_SEQUENCES_PER_WINDOW) {
            return false; // rate limit: no unlimited bounce grace
        }
        recentLaunches.addLast(now);
        sequenceCounter++;
        state = State.RISING;
        launchVelocity = deltaY;
        risingTicks = 0;
        arcDisplacement = Math.abs(deltaY);
        launchedAtMillis = now;
        previousRisingDeltaY = deltaY;
        previousDeltaY = deltaY;
        return true;
    }

    private void acceptRisingTick(double deltaY, boolean firstTick) {
        if (firstTick) {
            risingTicks = 0;
            arcDisplacement = Math.abs(deltaY);
        } else {
            arcDisplacement += Math.abs(deltaY);
        }
        previousRisingDeltaY = deltaY;
        previousDeltaY = deltaY;
    }

    private void endSequence() {
        state = State.IDLE;
        launchVelocity = 0.0D;
        previousRisingDeltaY = 0.0D;
        arcDisplacement = 0.0D;
        risingTicks = 0;
        launchedAtMillis = 0L;
        // The sequence id counter is intentionally monotonic: diagnostics see every arc ever launched.
    }

    /** Full reset (teleports, TPS collapse). Any in-flight arc is discarded. */
    public synchronized void resetState() {
        state = State.IDLE;
        launchVelocity = 0.0D;
        previousRisingDeltaY = 0.0D;
        arcDisplacement = 0.0D;
        risingTicks = 0;
        launchedAtMillis = 0L;
        previousDeltaY = 0.0D;
    }

    private void evict(long now) {
        while (!recentLaunches.isEmpty() && now - recentLaunches.peekFirst() > WINDOW_MILLIS) {
            recentLaunches.pollFirst();
        }
    }

    /** Number of bounce sequences currently tracked inside the rolling window at logical time {@code now} (diagnostics/tests). */
    public synchronized int sequencesInWindow(long now) {
        evict(now);
        return recentLaunches.size();
    }

    /** True while an arc is in flight (diagnostics/tests). */
    public synchronized boolean isInBounceSequence() {
        return state == State.RISING;
    }

    /** Monotonic count of launched bounce sequences (diagnostics/tests). */
    public synchronized long sequenceCount() {
        return sequenceCounter;
    }
}
