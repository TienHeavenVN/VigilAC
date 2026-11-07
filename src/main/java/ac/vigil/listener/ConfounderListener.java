package ac.vigil.listener;

import java.util.function.Supplier;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerVelocityEvent;
import org.bukkit.entity.Player;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.data.PlayerDataManager;

/**
 * Tracks server-side movement modifiers that invalidate normal movement
 * expectations: teleports and velocity (knockback). Each sets a timed grace
 * window consulted by movement checks.
 */
public final class ConfounderListener implements Listener {

    private final PlayerDataManager dataManager;
    private final Supplier<VigilACConfig> config;

    public ConfounderListener(PlayerDataManager dataManager, Supplier<VigilACConfig> config) {
        this.dataManager = dataManager;
        this.config = config;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        PlayerData data = dataManager.get(event.getPlayer().getUniqueId());
        if (data != null) {
            long now = System.currentTimeMillis();
            data.setTeleportGraceUntil(now + config.get().teleportGraceMillis());
            data.monitor().noteTeleport(String.valueOf(event.getCause()));
        }
    }

    /**
     * Damage bookkeeping, separated by cause. ONLY fall damage resets the
     * no-fall suspicious-landing streak: unrelated damage (zombies, lava,
     * drowning) is recorded for the monitor but must never suppress fall
     * evidence. The FALL case additionally serves as an ordering backstop —
     * if the landing evaluation ran before the damage event on the same
     * tick, the next landing re-checks the fall-damage signal and the grace
     * window in NoFallCheck absorbs the tick skew.
     */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (!(event.getEntity() instanceof Player player)) {
            return;
        }
        PlayerData data = dataManager.get(player.getUniqueId());
        if (data == null) {
            return;
        }
        boolean fall = event.getCause() == EntityDamageEvent.DamageCause.FALL;
        data.setLastDamageAt(System.currentTimeMillis());
        data.monitor().noteDamage(fall);
        if (fall) {
            data.movementTracker().resetSuspiciousLandings();
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onVelocity(PlayerVelocityEvent event) {
        PlayerData data = dataManager.get(event.getPlayer().getUniqueId());
        if (data == null) {
            return;
        }
        double speed = event.getVelocity().length();
        // Stronger impulses deserve slightly longer grace, capped at 2 seconds.
        long grace = config.get().velocityGraceMillis() + (long) (speed * 100.0D);
        long now = System.currentTimeMillis();
        data.setKnockbackGraceUntil(now + Math.min(grace, 2_000L));
        data.monitor().noteVelocity(speed);
    }
}
