package ac.vigil.command;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import ac.vigil.VigilAC;
import ac.vigil.check.Check;
import ac.vigil.data.PlayerData;
import ac.vigil.util.Text;

/**
 * {@code /vigilac} command tree. Every subcommand performs its own
 * permission check; {@code vigilac.command} is only the entry point.
 */
public final class VigilACCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("info", "alerts", "check", "violations", "debug", "reload", "exempt", "help");

    private final VigilAC plugin;

    public VigilACCommand(VigilAC plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("vigilac.command")) {
            sender.sendMessage(Text.colorize("&cYou do not have permission to use this command."));
            return true;
        }
        if (args.length == 0 || args[0].equalsIgnoreCase("help")) {
            sendHelp(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "info" -> info(sender);
            case "alerts" -> alerts(sender);
            case "check" -> check(sender, args);
            case "violations" -> violations(sender, args);
            case "debug" -> debug(sender, args);
            case "reload" -> reload(sender);
            case "exempt" -> exempt(sender, args);
            default -> sender.sendMessage(Text.colorize("&cUnknown subcommand. Use &e/vigilac help&c."));
        }
        return true;
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage(Text.colorize("&6VigilAC &7— anti-cheat"));
        sender.sendMessage(Text.colorize("&e/vigilac info &7— version, TPS, checks, storage status"));
        if (sender.hasPermission("vigilac.alerts")) sender.sendMessage(Text.colorize("&e/vigilac alerts &7— toggle alerts for yourself"));
        if (sender.hasPermission("vigilac.check")) sender.sendMessage(Text.colorize("&e/vigilac check [player] [check] &7— current violation levels"));
        if (sender.hasPermission("vigilac.violations")) sender.sendMessage(Text.colorize("&e/vigilac violations [player] &7— violation overview"));
        if (sender.hasPermission("vigilac.debug")) sender.sendMessage(Text.colorize("&e/vigilac debug [player] [on|off] &7— per-player detection debug output"));
        if (sender.hasPermission("vigilac.reload")) sender.sendMessage(Text.colorize("&e/vigilac reload &7— reload config.yml"));
        if (sender.hasPermission("vigilac.exempt")) sender.sendMessage(Text.colorize("&e/vigilac exempt <player> [check|*] &7— toggle check exemptions"));
    }

    private void info(CommandSender sender) {
        if (!sender.hasPermission("vigilac.info")) {
            noPerm(sender);
            return;
        }
        double tps = 20.0D;
        double[] tpsValues = Bukkit.getServer().getTPS();
        if (tpsValues.length > 0) {
            tps = tpsValues[0];
        }
        sender.sendMessage(Text.colorize("&6VigilAC &fv" + plugin.getDescription().getVersion()));
        sender.sendMessage(Text.colorize("&7Server: &e" + Bukkit.getServer().getVersion()
                + " &7| Java: &e" + System.getProperty("java.version")
                + " &7| packetevents: &e" + plugin.getPacketEventsStatus()));
        sender.sendMessage(Text.colorize("&7Mode: &e" + (plugin.getVigilacConfig().isAlertOnly() ? "alert-only" : "punish")));
        sender.sendMessage(Text.colorize(String.format(java.util.Locale.US, "&7TPS: &e%.1f &7| Online: &e%d &7| Tracked: &e%d",
                tps, Bukkit.getOnlinePlayers().size(), plugin.getPlayerDataManager().getTrackedCount())));
        StringBuilder names = new StringBuilder();
        for (Check<?> check : plugin.getCheckManager().getAll()) {
            if (names.length() > 0) {
                names.append(", ");
            }
            names.append(check.getName());
        }
        sender.sendMessage(Text.colorize("&7Checks (" + plugin.getCheckManager().getCount() + "): &e" + names));
        String storage = plugin.getDatabaseManager().isBroken()
                ? "broken (see console)"
                : (plugin.getDatabaseManager().isEnabled() ? "enabled" : "disabled");
        sender.sendMessage(Text.colorize("&7Violation storage: &e" + storage));
        if (plugin.getDatabaseManager().isEnabled() && plugin.getDatabaseManager().getTotalFlushedRows() > 0) {
            sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                    "&7  storage: last flush &e%.2f ms&7 (%d rows), total &e%d rows flushed",
                    plugin.getDatabaseManager().getLastFlushNanos() / 1_000_000.0D,
                    plugin.getDatabaseManager().getLastBatchRows(),
                    plugin.getDatabaseManager().getTotalFlushedRows())));
        }
        if (sender instanceof Player player) {
            PlayerData data = plugin.getPlayerDataManager().get(player.getUniqueId());
            if (data != null) {
                sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                        "&7Your packet rate: &e%.1f/s", data.getPacketsPerSecond())));
            }
        }
    }

    private void alerts(CommandSender sender) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Alerts are per-player; the console always receives them.");
            return;
        }
        if (!player.hasPermission("vigilac.alerts")) {
            noPerm(sender);
            return;
        }
        boolean enabled = plugin.getAlertManager().toggle(player.getUniqueId());
        player.sendMessage(Text.colorize(enabled
                ? "&aVigilAC alerts enabled."
                : "&eVigilAC alerts muted (you can still see /vigilac info)."));
    }

    private void check(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.check")) {
            noPerm(sender);
            return;
        }
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Text.colorize("&e" + target.getName() + " is not being tracked yet."));
            return;
        }
        String filterCheck = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : null;
        Map<String, Double> levels = data.getViolationLevelsSnapshot();
        boolean printed = false;
        for (Map.Entry<String, Double> entry : levels.entrySet()) {
            if (filterCheck != null && !entry.getKey().equals(filterCheck)) {
                continue;
            }
            sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                    "&7%s: &e%.2f vl", entry.getKey(), entry.getValue())));
            printed = true;
        }
        if (!printed) {
            sender.sendMessage(Text.colorize(filterCheck != null
                    ? "&a" + target.getName() + " has no violations for check '" + filterCheck + "'."
                    : "&a" + target.getName() + " has no violations."));
        }
    }

    private void violations(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.violations")) {
            noPerm(sender);
            return;
        }
        Player target = resolveTarget(sender, args, 1);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Text.colorize("&e" + target.getName() + " is not being tracked yet."));
            return;
        }
        Map<String, Double> levels = data.getViolationLevelsSnapshot();
        double total = levels.values().stream().mapToDouble(Double::doubleValue).sum();
        sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                "&6Violations for %s &7(total tracked VL: &e%.2f&7):", target.getName(), total)));
        if (levels.isEmpty()) {
            sender.sendMessage(Text.colorize("&7  none"));
            return;
        }
        levels.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .forEach(entry -> sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                        "&7  %s: &e%.2f", entry.getKey(), entry.getValue()))));
    }

    private void debug(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.debug")) {
            noPerm(sender);
            return;
        }
        Player target;
        Boolean onOff = null;
        if (args.length >= 2 && (args[1].equalsIgnoreCase("on") || args[1].equalsIgnoreCase("off"))) {
            if (!(sender instanceof Player player)) {
                sender.sendMessage("Console must name a player: /vigilac debug <player> on|off");
                return;
            }
            target = player;
            onOff = args[1].equalsIgnoreCase("on");
        } else {
            target = args.length >= 2 ? Bukkit.getPlayerExact(args[1]) : (sender instanceof Player player ? player : null);
            if (args.length >= 3) {
                onOff = args[2].equalsIgnoreCase("on");
            }
        }
        if (target == null) {
            sender.sendMessage(Text.colorize("&cPlayer not found (or console without a target)."));
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().load(target.getUniqueId());
        boolean enabled = onOff != null ? onOff : !data.isDebug();
        data.setDebug(enabled);
        sender.sendMessage(Text.colorize("&aDebug " + (enabled ? "enabled" : "disabled") + " for " + target.getName()));
    }

    private void reload(CommandSender sender) {
        if (!sender.hasPermission("vigilac.reload")) {
            noPerm(sender);
            return;
        }
        plugin.reloadConfiguration();
        sender.sendMessage(Text.colorize("&aVigilAC configuration reloaded."));
    }

    private void exempt(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.exempt")) {
            noPerm(sender);
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.colorize("&cUsage: /vigilac exempt <player> [check|*]"));
            return;
        }
        Player target = Bukkit.getPlayerExact(args[1]);
        if (target == null) {
            sender.sendMessage(Text.colorize("&cPlayer not found: " + args[1]));
            return;
        }
        String checkName = args.length >= 3 ? args[2].toLowerCase(Locale.ROOT) : "*";
        boolean exempt = plugin.getExemptionManager().toggle(target.getUniqueId(), checkName);
        sender.sendMessage(Text.colorize(String.format(
                "&a%s is now %s check '%s'.",
                target.getName(),
                exempt ? "exempt from" : "no longer exempt from",
                checkName.equals("*") ? "ALL" : checkName)));
    }

    /** Resolves the optional [player] argument; defaults to the sender if a player. */
    private Player resolveTarget(CommandSender sender, String[] args, int index) {
        if (args.length > index) {
            Player target = Bukkit.getPlayerExact(args[index]);
            if (target == null) {
                sender.sendMessage(Text.colorize("&cPlayer not found: " + args[index]));
            }
            return target;
        }
        if (sender instanceof Player player) {
            return player;
        }
        sender.sendMessage(Text.colorize("&cConsole must name a player."));
        return null;
    }

    private void noPerm(CommandSender sender) {
        sender.sendMessage(Text.colorize("&cYou do not have permission to do that."));
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("vigilac.command")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return filter(SUBCOMMANDS, args[0]);
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length == 2 && (sub.equals("check") || sub.equals("violations") || sub.equals("debug") || sub.equals("exempt"))) {
            return filter(onlineNames(), args[1]);
        }
        if (args.length == 3 && sub.equals("check")) {
            List<String> names = new ArrayList<>();
            for (Check<?> check : plugin.getCheckManager().getAll()) {
                names.add(check.getName());
            }
            return filter(names, args[2]);
        }
        if (args.length == 3 && sub.equals("debug")) {
            return filter(List.of("on", "off"), args[2]);
        }
        if (args.length == 3 && sub.equals("exempt")) {
            List<String> names = new ArrayList<>(List.of("*"));
            for (Check<?> check : plugin.getCheckManager().getAll()) {
                names.add(check.getName());
            }
            return filter(names, args[2]);
        }
        return Collections.emptyList();
    }

    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            names.add(player.getName());
        }
        return names;
    }

    private static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) {
                matches.add(option);
            }
        }
        return matches;
    }
}
