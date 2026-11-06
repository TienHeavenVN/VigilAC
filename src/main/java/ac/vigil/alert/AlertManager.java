package ac.vigil.alert;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

/**
 * Delivers alerts to staff. Recipients: the console plus online players
 * holding {@code vigilac.alerts}, minus players who muted alerts via
 * {@code /vigilac alerts}. Mute state is per-session (resets on restart).
 */
public final class AlertManager {

    private final Set<UUID> muted = ConcurrentHashMap.newKeySet();

    /** Broadcast a staff-visible alert. */
    public void broadcast(String message) {
        String formatted = "[VigilAC] " + message;
        Bukkit.getConsoleSender().sendMessage(formatted);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("vigilac.alerts") && !muted.contains(staff.getUniqueId())) {
                staff.sendMessage(formatted);
            }
        }
    }

    /** Broadcast raw debug info to players holding {@code vigilac.debug}. */
    public void notifyDebug(String message) {
        String formatted = "[VigilAC debug] " + message;
        Bukkit.getConsoleSender().sendMessage(formatted);
        for (Player staff : Bukkit.getOnlinePlayers()) {
            if (staff.hasPermission("vigilac.debug") && !muted.contains(staff.getUniqueId())) {
                staff.sendMessage(formatted);
            }
        }
    }

    /** Toggles alert delivery for one player. Returns true if alerts are now enabled. */
    public boolean toggle(UUID uuid) {
        if (muted.contains(uuid)) {
            muted.remove(uuid);
            return true;
        }
        muted.add(uuid);
        return false;
    }

    public boolean isEnabled(UUID uuid) {
        return !muted.contains(uuid);
    }
}
