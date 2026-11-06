package ac.vigil.punishment;

import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import ac.vigil.alert.AlertManager;
import ac.vigil.config.VigilACConfig;
import ac.vigil.storage.DatabaseManager;
import ac.vigil.storage.ViolationRecord;
import ac.vigil.util.Text;
import ac.vigil.violation.Verdict;
import ac.vigil.violation.Violation;

/**
 * Turns verdicts into actions.
 *
 * <p>Default behaviour is <b>alert-only</b> ({@code mode: alert-only}): nothing
 * beyond staff alerts happens, including at the KICK threshold — the server
 * operator must explicitly opt into punishments with {@code mode: punish}.</p>
 */
public final class PunishmentManager {

    private final Supplier<VigilACConfig> config;
    private final AlertManager alerts;
    private final DatabaseManager database;

    public PunishmentManager(Supplier<VigilACConfig> config, AlertManager alerts, DatabaseManager database) {
        this.config = config;
        this.alerts = alerts;
        this.database = database;
    }

    public void handle(Violation violation, Verdict verdict) {
        if (verdict == Verdict.NONE) {
            return;
        }
        VigilACConfig cfg = config.get();
        Player target = Bukkit.getPlayer(violation.getPlayer());
        String name = target != null ? target.getName() : violation.getPlayer().toString();
        String check = violation.getCheckName();
        String line = String.format(java.util.Locale.US,
                "%s flagged for %s (vl=%.1f, +%.2f)",
                name, check, violation.getViolationLevel(), violation.getDelta());

        switch (verdict) {
            case ALERT -> alerts.broadcast(line);
            case WARN -> {
                alerts.broadcast("WARN: " + line);
                if (target != null) {
                    target.sendMessage(Text.colorize(cfg.warnMessage())
                            .replace("%check%", check)
                            .replace("%player%", target.getName()));
                }
                runCommands(cfg.commandsOnWarn(), target, name, check);
            }
            case KICK -> {
                if (cfg.isAlertOnly()) {
                    alerts.broadcast("KICK-LEVEL (alert-only, no action): " + line);
                } else if (target != null) {
                    target.kickPlayer(Text.colorize(cfg.kickMessage())
                            .replace("%check%", check)
                            .replace("%player%", target.getName()));
                    alerts.broadcast("Kicked " + line);
                } else {
                    alerts.broadcast("Kick-level (target offline): " + line);
                }
                runCommands(cfg.commandsOnKick(), target, name, check);
            }
            default -> { }
        }
        if (cfg.storageEnabled()) {
            database.enqueue(new ViolationRecord(
                    violation.getPlayer(),
                    check,
                    violation.getViolationLevel(),
                    violation.getDebug(),
                    violation.getTimeMillis()));
        }
    }

    /** Executes configured console commands with %player%/%check% substitution. */
    private void runCommands(java.util.List<String> commands, Player target, String name, String check) {
        if (commands == null || commands.isEmpty()) {
            return;
        }
        String playerName = target != null ? target.getName() : name;
        ConsoleCommandSender console = Bukkit.getConsoleSender();
        for (String raw : commands) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String command = raw.replace("%player%", playerName).replace("%check%", check);
            try {
                Bukkit.dispatchCommand(console, command);
            } catch (RuntimeException ex) {
                alerts.broadcast("Failed to run punishment command '" + command + "': " + ex.getMessage());
            }
        }
    }
}
