package ac.vigil.config;

/**
 * Per-check tunables resolved from config. Pure value type so the violation
 * pipeline is unit-testable without a server.
 */
public final class CheckSettings {

    private final String name;
    private final boolean enabled;
    private final double alertVl;
    private final double warnVl;
    private final double kickVl;
    private final double maxVl;

    public CheckSettings(String name, boolean enabled, double alertVl, double warnVl, double kickVl, double maxVl) {
        this.name = name;
        this.enabled = enabled;
        this.alertVl = alertVl;
        this.warnVl = warnVl;
        this.kickVl = kickVl;
        this.maxVl = maxVl;
    }

    public String getName() { return name; }
    public boolean isEnabled() { return enabled; }
    public double getAlertVl() { return alertVl; }
    public double getWarnVl() { return warnVl; }
    /** VL at which a kick is attempted; values &le; 0 disable kicking for this check. */
    public double getKickVl() { return kickVl; }
    public double getMaxVl() { return maxVl; }
}
