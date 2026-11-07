package ac.vigil.check.combat;

/**
 * Pure vector math for combat analysis: MC (yaw, pitch) look vectors,
 * eye-to-box angles. No Bukkit, fully unit-testable.
 *
 * <p>MC convention: yaw 0 faces +Z and increases clockwise; pitch 0 is
 * horizontal with negative values looking up. The direction vector is exactly
 * the one vanilla derives from the entity rotation.</p>
 */
public final class AttackGeometry {

    private AttackGeometry() {
    }

    public static double[] lookVector(float yaw, float pitch) {
        double yawRad = Math.toRadians(yaw);
        double pitchRad = Math.toRadians(pitch);
        return new double[]{
                -Math.sin(yawRad) * Math.cos(pitchRad),
                -Math.sin(pitchRad),
                Math.cos(yawRad) * Math.cos(pitchRad)
        };
    }

    /** Angle (degrees) between the (yaw, pitch) look vector and a normalized direction. */
    public static double angleBetween(float yaw, float pitch, double dirX, double dirY, double dirZ) {
        double[] look = lookVector(yaw, pitch);
        double dot = look[0] * dirX + look[1] * dirY + look[2] * dirZ;
        dot = Math.max(-1.0D, Math.min(1.0D, dot));
        return Math.toDegrees(Math.acos(dot));
    }

    /** Angle (degrees) between the (yaw, pitch) look vector and an arbitrary (non-zero) direction vector. */
    public static double angleToDirection(float yaw, float pitch, double dx, double dy, double dz) {
        double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (len < 1.0E-7D) {
            return 0.0D;
        }
        return angleBetween(yaw, pitch, dx / len, dy / len, dz / len);
    }

    /**
     * Closest point on an axis-aligned box to the eye position. For a
     * player standing outside the box, this is the clamp of the eye
     * coordinates into the box ranges.
     */
    public static double[] closestPointOnBox(double eyeX, double eyeY, double eyeZ,
                                             double minX, double minY, double minZ,
                                             double maxX, double maxY, double maxZ) {
        return new double[]{
                clamp(eyeX, minX, maxX),
                clamp(eyeY, minY, maxY),
                clamp(eyeZ, minZ, maxZ)
        };
    }

    /**
     * Angle (degrees) between the look direction and the nearest surface
     * point of the target box as seen from the eye — the quantity an aim
     * assist needs to hit, and strictly tighter than angle-to-centroid for
     * tall entities (enderman) when the player looks at the legs.
     */
    public static double angleToClosestPointOnBox(float yaw, float pitch,
                                                  double eyeX, double eyeY, double eyeZ,
                                                  double minX, double minY, double minZ,
                                                  double maxX, double maxY, double maxZ) {
        double[] cp = closestPointOnBox(eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ);
        return angleToDirection(yaw, pitch, cp[0] - eyeX, cp[1] - eyeY, cp[2] - eyeZ);
    }

    public static double shortestAngleDegrees(float from, float to) {
        double diff = (to - from) % 360.0D;
        if (diff > 180.0D) {
            diff -= 360.0D;
        } else if (diff < -180.0D) {
            diff += 360.0D;
        }
        return Math.abs(diff);
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : Math.min(v, hi);
    }
}
