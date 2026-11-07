package ac.vigil.check;

import java.util.function.Supplier;
import org.bukkit.entity.Player;
import ac.vigil.alert.AlertManager;
import ac.vigil.config.VigilACConfig;
import ac.vigil.data.PlayerData;
import ac.vigil.exempt.ExemptionManager;

/**
 * Shared path between listeners and the violation manager: handles per-check
 * exemptions, debug broadcasting, and forwards to {@code ViolationManager}.
 */
public final class ViolationPipeline {

    private final Supplier<VigilACConfig> config;
    private final ExemptionManager exemptions;
    private final ac.vigil.violation.ViolationManager violations;
    private final AlertManager alerts;

    public ViolationPipeline(Supplier<VigilACConfig> config,
                             ExemptionManager exemptions,
                             ac.vigil.violation.ViolationManager violations,
                             AlertManager alerts) {
        this.config = config;
        this.exemptions = exemptions;
        this.violations = violations;
        this.alerts = alerts;
    }

    public ViolationCallback forPlayer(Player player, PlayerData data) {
        return (check, vl, debug) -> {
            if (exemptions.isExempt(player.getUniqueId(), check.getName())) {
                return;
            }
            VigilACConfig cfg = config.get();
            if (cfg.debugEnabled() || data.isDebug()) {
                alerts.notifyDebug("[" + check.getName() + "] " + player.getName() + ": " + debug);
            }
            if (vl > 0.0D) {
                violations.record(player.getUniqueId(), check.getName(), vl, debug, data);
            }
        };
    }
}
