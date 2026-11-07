package ac.vigil.data.tracking;

/**
 * Per-player state machine for the block-digging packet lifecycle:
 * {@code START_DIGGING} → {@code FINISHED_DIGGING} (completed) or
 * {@code CANCELLED_DIGGING} (aborted).
 *
 * <p>Vanilla resets break progress when the target position changes or the
 * held item changes mid-dig, so both events restart/abort bookkeeping:</p>
 * <ul>
 *   <li>START at a different position while a dig is active → implicit abort
 *       of the old dig ({@code abandonedDigs}++), new dig begins.</li>
 *   <li>START at the same position → the dig's start time is reset (vanilla
 *       treats re-digging as progress reset).</li>
 *   <li>Held-item change mid-dig → {@link ActiveDig#toolChanged} set; callers
 *       must treat dig timing as inconclusive (no impossible-break VL).</li>
 * </ul>
 *
 * <p>All methods O(1); fixed state (no history), so memory is O(1).</p>
 */
public final class DiggingTracker {

    public static final class ActiveDig {
        private final int x, y, z;
        private final long startedAt;
        private boolean toolChanged;
        private boolean directionChanged;

        ActiveDig(int x, int y, int z, long startedAt) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.startedAt = startedAt;
        }

        public int getX() { return x; }
        public int getY() { return y; }
        public int getZ() { return z; }
        public long getStartedAt() { return startedAt; }
        public boolean isToolChanged() { return toolChanged; }
        public boolean isDirectionChanged() { return directionChanged; }

        void markToolChanged() { this.toolChanged = true; }
        void markDirectionChanged() { this.directionChanged = true; }

        boolean samePosition(int x, int y, int z) {
            return this.x == x && this.y == y && this.z == z;
        }
    }

    public static final class CompletedDig {
        private final ActiveDig dig;
        private final long completedAt;

        CompletedDig(ActiveDig dig, long completedAt) {
            this.dig = dig;
            this.completedAt = completedAt;
        }

        public ActiveDig getDig() { return dig; }
        public long getCompletedAt() { return completedAt; }
        public long getDurationMillis() { return completedAt - dig.startedAt; }
    }

    private ActiveDig active;
    private long abandonedDigs;
    private long abortedDigs;
    private long completedDigs;

    /** START_DIGGING received. Restarts bookkeeping per vanilla semantics. */
    public synchronized void start(int x, int y, int z, long now) {
        if (active != null) {
            if (active.samePosition(x, y, z)) {
                active = new ActiveDig(x, y, z, now);
                return;
            }
            abandonedDigs++;
        }
        active = new ActiveDig(x, y, z, now);
    }

    /** CANCELLED_DIGGING received (client abort). Returns the aborted dig, or null. */
    public synchronized ActiveDig abort(long now) {
        ActiveDig d = active;
        if (d != null) {
            abortedDigs++;
            active = null;
        }
        return d;
    }

    /** FINISHED_DIGGING received. Returns the completed dig, or null when none active. */
    public synchronized CompletedDig complete(long now) {
        ActiveDig d = active;
        if (d == null) {
            return null;
        }
        active = null;
        completedDigs++;
        return new CompletedDig(d, now);
    }

    /**
     * Ends any active dig without recording completion — used when the server
     * observes a break completion outside the tracked flow (e.g. BlockBreakEvent),
     * so a completed-but-untracked block does not linger.
     */
    public synchronized ActiveDig drain() {
        ActiveDig d = active;
        active = null;
        return d;
    }

    /** Held-item change: if a dig is active, mark it inconclusive. */
    public synchronized void noteHeldItemChange() {
        if (active != null) {
            active.markToolChanged();
        }
    }

    /** 26.3+ CHANGE_DESTROY_DIRECTION (re-aiming the destroy): mark the dig. */
    public synchronized void noteDirectionChange() {
        if (active != null) {
            active.markDirectionChanged();
        }
    }

    public synchronized ActiveDig current() {
        return active;
    }

    public synchronized long abandonedDigs() { return abandonedDigs; }
    public synchronized long abortedDigs() { return abortedDigs; }
    public synchronized long completedDigs() { return completedDigs; }

    public synchronized void clear() {
        active = null;
    }
}
