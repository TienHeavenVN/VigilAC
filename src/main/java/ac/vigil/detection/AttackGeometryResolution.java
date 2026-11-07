package ac.vigil.detection;

/**
 * Result of resolving an attack attempt into world facts, computed on the
 * main thread. Pure data; distance math is delegated to
 * {@link AttackContext#distanceEyeToBox()}.
 *
 * <p>{@code found} is false when the target could not be resolved (attacker
 * offline, entity gone, entity in another world). {@code attackRangeMax} is
 * non-null only when a reliable item-specific range was read from the held
 * item (e.g. a verified data-component read); null means "unknown — the
 * configured cap applies", never a guess.</p>
 */
public final class AttackGeometryResolution {

    private final boolean found;
    private final String reason;          // null when found
    private final double eyeX, eyeY, eyeZ;
    private final double boxMinX, boxMinY, boxMinZ;
    private final double boxMaxX, boxMaxY, boxMaxZ;
    private final String targetType;      // "unknown" when unresolved
    private final Double attackRangeMax;  // null = unknown component
    private final String rangeSource;     // "component" | "unknown"
    private final String itemContext;     // material name of the held item, "AIR" when empty

    private AttackGeometryResolution(boolean found, String reason,
                                     double eyeX, double eyeY, double eyeZ,
                                     double boxMinX, double boxMinY, double boxMinZ,
                                     double boxMaxX, double boxMaxY, double boxMaxZ,
                                     String targetType, Double attackRangeMax,
                                     String rangeSource, String itemContext) {
        this.found = found;
        this.reason = reason;
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.boxMinX = boxMinX;
        this.boxMinY = boxMinY;
        this.boxMinZ = boxMinZ;
        this.boxMaxX = boxMaxX;
        this.boxMaxY = boxMaxY;
        this.boxMaxZ = boxMaxZ;
        this.targetType = targetType == null ? "unknown" : targetType;
        this.attackRangeMax = attackRangeMax;
        this.rangeSource = rangeSource == null ? "unknown" : rangeSource;
        this.itemContext = itemContext == null ? "AIR" : itemContext;
    }

    public static AttackGeometryResolution resolved(double eyeX, double eyeY, double eyeZ,
                                                    double boxMinX, double boxMinY, double boxMinZ,
                                                    double boxMaxX, double boxMaxY, double boxMaxZ,
                                                    String targetType, Double attackRangeMax,
                                                    String rangeSource, String itemContext) {
        return new AttackGeometryResolution(true, null, eyeX, eyeY, eyeZ,
                boxMinX, boxMinY, boxMinZ, boxMaxX, boxMaxY, boxMaxZ,
                targetType, attackRangeMax, rangeSource, itemContext);
    }

    public static AttackGeometryResolution unresolved(String reason, String itemContext) {
        return new AttackGeometryResolution(false, reason, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                "unknown", null, "unknown", itemContext);
    }

    public boolean isFound() { return found; }
    public String getReason() { return reason; }
    public double getEyeX() { return eyeX; }
    public double getEyeY() { return eyeY; }
    public double getEyeZ() { return eyeZ; }
    public double getBoxMinX() { return boxMinX; }
    public double getBoxMinY() { return boxMinY; }
    public double getBoxMinZ() { return boxMinZ; }
    public double getBoxMaxX() { return boxMaxX; }
    public double getBoxMaxY() { return boxMaxY; }
    public double getBoxMaxZ() { return boxMaxZ; }
    public String getTargetType() { return targetType; }
    public Double getAttackRangeMax() { return attackRangeMax; }
    public String getRangeSource() { return rangeSource; }
    public String getItemContext() { return itemContext; }

    /** Distance from the attacker's eye to the closest point of the resolved target box. */
    public double distanceEyeToBox() {
        return AttackContext.distanceEyeToBox(eyeX, eyeY, eyeZ, boxMinX, boxMinY, boxMinZ,
                boxMaxX, boxMaxY, boxMaxZ);
    }
}
