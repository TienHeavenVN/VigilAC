package ac.vigil.storage;

import java.util.UUID;

/** Flat row for the violations table. */
public final class ViolationRecord {

    private final UUID player;
    private final String checkName;
    private final double violationLevel;
    private final String debug;
    private final long createdMillis;

    public ViolationRecord(UUID player, String checkName, double violationLevel, String debug, long createdMillis) {
        this.player = player;
        this.checkName = checkName;
        this.violationLevel = violationLevel;
        this.debug = debug;
        this.createdMillis = createdMillis;
    }

    public UUID getPlayer() { return player; }
    public String getCheckName() { return checkName; }
    public double getViolationLevel() { return violationLevel; }
    public String getDebug() { return debug; }
    public long getCreatedMillis() { return createdMillis; }
}
