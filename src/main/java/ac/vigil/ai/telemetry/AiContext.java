package ac.vigil.ai.telemetry;

/**
 * Server-side context supplied to the extractor that cannot come from
 * {@code PlayerData} alone (it needs a clock/Bukkit view). Pure data — this
 * keeps {@code FeatureExtractor} unit-testable without a server.
 */
public final class AiContext {

    private final long nowMillis;
    private final double pingMillis;
    private final double tps1m;
    private final boolean onGround;
    private final boolean freshVelocityExemption;
    private final boolean freshTeleportExemption;
    private final int combatIntervalSamples;

    public AiContext(long nowMillis, double pingMillis, double tps1m, boolean onGround,
                     boolean freshVelocityExemption, boolean freshTeleportExemption,
                     int combatIntervalSamples) {
        this.nowMillis = nowMillis;
        this.pingMillis = pingMillis;
        this.tps1m = tps1m;
        this.onGround = onGround;
        this.freshVelocityExemption = freshVelocityExemption;
        this.freshTeleportExemption = freshTeleportExemption;
        this.combatIntervalSamples = combatIntervalSamples;
    }

    public long nowMillis() { return nowMillis; }
    public double pingMillis() { return pingMillis; }
    public double tps1m() { return tps1m; }
    public boolean onGround() { return onGround; }
    public boolean freshVelocityExemption() { return freshVelocityExemption; }
    public boolean freshTeleportExemption() { return freshTeleportExemption; }
    public int combatIntervalSamples() { return combatIntervalSamples; }
}
