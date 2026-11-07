package ac.vigil.environment;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.BoundingBox;
import ac.vigil.detection.AttackGeometryResolution;
import ac.vigil.detection.AttackGeometryResolver;

/**
 * Bukkit-backed {@link AttackGeometryResolver}. Must only be called on the
 * main server thread.
 *
 * <h2>Resolution strategy and cost</h2>
 * The target is located by scanning entities within an 8-block cube around
 * the attacker's eye — comfortably beyond vanilla/item-supported melee
 * ranges — filtered by network entity id. The scan is bounded to nearby
 * entities, not the world, so per-attempt cost is O(nearby entities).
 * Unresolvable attempts (attacker offline, entity gone or in another world)
 * return {@code found=false} and never produce reach evidence.
 *
 * <p>Item-specific range is read from the main hand via
 * {@link AttackRangeResolver}; on failure the configured cap applies (no
 * invented values).</p>
 */
public final class BukkitAttackGeometryResolver implements AttackGeometryResolver {

    private static final double SCAN_RADIUS = 8.0D;

    @Override
    public AttackGeometryResolution resolve(java.util.UUID attacker, int entityId) {
        Player attackerPlayer = Bukkit.getPlayer(attacker);
        if (attackerPlayer == null || !attackerPlayer.isOnline()) {
            return AttackGeometryResolution.unresolved("attacker-offline", "AIR");
        }
        ItemStack hand = attackerPlayer.getInventory().getItemInMainHand();
        String itemContext = hand == null ? "AIR" : hand.getType().name();
        AttackRangeResolver.Result range = AttackRangeResolver.resolve(hand);

        Location eye = attackerPlayer.getEyeLocation();
        World world = attackerPlayer.getWorld();
        Entity target = findByEntityId(world, eye, entityId);
        if (target == null) {
            return AttackGeometryResolution.unresolved("entity-not-found", itemContext);
        }
        BoundingBox box = target.getBoundingBox();
        if (box == null) {
            return AttackGeometryResolution.unresolved("no-bounding-box", itemContext);
        }
        return AttackGeometryResolution.resolved(
                eye.getX(), eye.getY(), eye.getZ(),
                box.getMinX(), box.getMinY(), box.getMinZ(),
                box.getMaxX(), box.getMaxY(), box.getMaxZ(),
                target.getType().name(),
                range.getMaxRange(),
                range.getSource() == AttackRangeResolver.Source.COMPONENT ? "component" : "unknown",
                itemContext);
    }

    private static Entity findByEntityId(World world, Location eye, int entityId) {
        for (Entity candidate : world.getNearbyEntities(eye, SCAN_RADIUS, SCAN_RADIUS, SCAN_RADIUS)) {
            if (candidate.getEntityId() == entityId) {
                return candidate;
            }
        }
        return null;
    }
}
