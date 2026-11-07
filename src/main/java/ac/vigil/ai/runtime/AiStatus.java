package ac.vigil.ai.runtime;

import java.util.List;

/**
 * Point-in-time diagnostics of the AI module. Surfaced through the admin
 * command; pure data, no behavior. Counters are cumulative since start().
 */
public final class AiStatus {

    private final boolean enabled;
    private final boolean shadowOnly;
    private final String unavailableReason; // null when usable
    private final List<String> modelStatuses;
    private final long submitted;
    private final long droppedFull;
    private final long completed;
    private final long failed;
    private final long timedOut;
    private final long invalidSnapshots;
    private final double averageLatencyMillis;

    public AiStatus(boolean enabled, boolean shadowOnly, String unavailableReason, List<String> modelStatuses,
                    long submitted, long droppedFull, long completed, long failed, long timedOut,
                    long invalidSnapshots, double averageLatencyMillis) {
        this.enabled = enabled;
        this.shadowOnly = shadowOnly;
        this.unavailableReason = unavailableReason;
        this.modelStatuses = List.copyOf(modelStatuses);
        this.submitted = submitted;
        this.droppedFull = droppedFull;
        this.completed = completed;
        this.failed = failed;
        this.timedOut = timedOut;
        this.invalidSnapshots = invalidSnapshots;
        this.averageLatencyMillis = averageLatencyMillis;
    }

    public boolean enabled() { return enabled; }
    /** Always true today — the module only ever emits shadow evidence, never a verdict. */
    public boolean shadowOnly() { return shadowOnly; }
    public String unavailableReason() { return unavailableReason; }
    public List<String> modelStatuses() { return modelStatuses; }
    public long submitted() { return submitted; }
    public long droppedFull() { return droppedFull; }
    public long completed() { return completed; }
    public long failed() { return failed; }
    public long timedOut() { return timedOut; }
    public long invalidSnapshots() { return invalidSnapshots; }
    public double averageLatencyMillis() { return averageLatencyMillis; }

    public boolean usable() {
        return enabled && unavailableReason == null;
    }
}
