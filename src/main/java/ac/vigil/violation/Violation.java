package ac.vigil.violation;

import java.util.UUID;

/** One recorded evidence event. */
public final class Violation {

    private final UUID player;
    private final String checkName;
    private final double violationLevel;
    private final double delta;
    private final String debug;
    private final long timeMillis;

    public Violation(UUID player, String checkName, double violationLevel, double delta, String debug, long timeMillis) {
        this.player = player;
        this.checkName = checkName;
        this.violationLevel = violationLevel;
        this.delta = delta;
        this.debug = debug;
        this.timeMillis = timeMillis;
    }

    public UUID getPlayer() { return player; }
    public String getCheckName() { return checkName; }
    public double getViolationLevel() { return violationLevel; }
    public double getDelta() { return delta; }
    public String getDebug() { return debug; }
    public long getTimeMillis() { return timeMillis; }
}
