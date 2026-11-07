package ac.vigil.listener;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.entity.Player;
import ac.vigil.capture.CaptureService;
import ac.vigil.util.Text;

/**
 * Join/quit hooks for the consent-based capture. Bukkit-facing and deliberately thin;
 * all policy lives in {@link CaptureService}. On join, an UNconsented player may get
 * a single one-line notice — that notice is the consent flow's disclosure requirement,
 * and it is rate-limited by construction (once per join).
 */
public final class CaptureConsentListener implements Listener {

    private static final String NOTICE =
            "&7[VigilAC] This server may record &efeature-only&7 gameplay telemetry for anti-cheat research"
            + " &7(never your name, UUID, chat, inventory or IP). &e/vigilac capture consent&7 to opt in ·"
            + " &e/vigilac capture revoke&7 to opt out.";

    private final CaptureService capture;

    public CaptureConsentListener(CaptureService capture) {
        this.capture = capture;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (capture == null) {
            return;
        }
        capture.onLogin(player.getUniqueId());
        if (capture.shouldNotifyOnJoin(player.getUniqueId())) {
            player.sendMessage(Text.colorize(NOTICE));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        if (capture != null) {
            capture.onQuit(event.getPlayer().getUniqueId());
        }
    }
}
