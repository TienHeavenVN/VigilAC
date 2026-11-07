package ac.vigil.command;

import java.util.ArrayList;
import java.util.Arrays;
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
import ac.vigil.ai.AiServices;
import ac.vigil.ai.assistant.AdminAssistant;
import ac.vigil.ai.evidence.AiAssessment;
import ac.vigil.ai.report.PlayerProfile;
import ac.vigil.ai.runtime.AiStatus;
import ac.vigil.check.Check;
import ac.vigil.monitor.DetectionState;
import ac.vigil.data.PlayerData;
import ac.vigil.util.Text;

/**
 * {@code /vigilac} command tree. Every subcommand performs its own
 * permission check; {@code vigilac.command} is only the entry point.
 */
public final class VigilACCommand implements CommandExecutor, TabCompleter {

    private static final List<String> SUBCOMMANDS =
            List.of("info", "alerts", "check", "violations", "debug", "reload", "exempt", "monitor", "ai", "help");

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
            case "monitor" -> monitor(sender, args);
            case "ai" -> ai(sender, args);
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
        if (sender.hasPermission("vigilac.monitor")) sender.sendMessage(Text.colorize("&e/vigilac monitor <player> &7— continuous monitoring state + per-check detection status"));
        if (sender.hasPermission("vigilac.ai")) sender.sendMessage(Text.colorize("&e/vigilac ai <player|status|assistant|ask> &7— AI-assist report / module status (advisory; never punishes)"));
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


    private void monitor(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.monitor")) {
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
        ac.vigil.monitor.PlayerMonitor monitor = data.monitor();
        long now = monitor.nowMillis();
        int online = Bukkit.getOnlinePlayers().size();

        sender.sendMessage(Text.colorize("&6Monitoring &e" + target.getName()
                + " &7(world=&e" + monitor.worldName() + "&7, gamemode=&e" + monitor.gameMode()
                + "&7, online=&e" + online + "&7)"));

        StringBuilder flags = new StringBuilder();
        if (monitor.isOnGround()) flags.append("onGround ");
        if (monitor.isSprinting()) flags.append("sprint ");
        if (monitor.isSneaking()) flags.append("sneak ");
        if (monitor.isGliding()) flags.append("elytra ");
        if (monitor.isSwimming()) flags.append("swim ");
        if (monitor.isInVehicle()) flags.append("vehicle ");
        if (monitor.isLevitation()) flags.append("levitation ");
        if (monitor.isSlowFalling()) flags.append("slow-falling ");
        if (monitor.speedPotionLevel() > 0) flags.append("speed-").append(monitor.speedPotionLevel()).append(' ');
        if (flags.length() == 0) flags.append("none ");
        sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                "&7  state: &e%s&7| ping=&e%.0fms&7 h-vel=&e%.2f b/s&7 v-vel=&e%.2f b/s",
                flags.toString().trim(), monitor.pingEma(), monitor.velocityHorizontalEma(), monitor.velocityVerticalEma())));

        sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                "&7  observations=&e%d&7 packets=&e%.1f/s&7 evidence=&e%d&7(cap %d)",
                monitor.totalObservations(), data.getPacketsPerSecond(),
                monitor.evidenceCount(), ac.vigil.monitor.EvidenceStore.DEFAULT_CAPACITY)));

        double lastDescent = monitor.lastLandingDescent();
        sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                "&7  last landing: &e%.2f blocks&7 | teleport=&e%s velocity=&e%s fallDamage=&e%s otherDamage=&e%s",
                lastDescent,
                ageText(now, monitor.lastTeleportAt()),
                ageText(now, monitor.lastVelocityAt()),
                ageText(now, monitor.lastFallDamageAt()),
                ageText(now, monitor.lastGenericDamageAt()))));

        sender.sendMessage(Text.colorize("&7  checks (state vl conf evidence last-detection):"));
        Map<String, Double> levels = data.getViolationLevelsSnapshot();
        for (Check<?> check : plugin.getCheckManager().getAll()) {
            String name = check.getName();
            double vl = levels.getOrDefault(name, 0.0D);
            double alertVl = 5.0D;
            boolean enabled = true;
            try {
                var settings = plugin.getVigilacConfig().checkSettings(name);
                if (settings != null) {
                    alertVl = settings.getAlertVl();
                    enabled = settings.isEnabled();
                }
            } catch (RuntimeException ignored) {
                // keep defaults; rendering must never throw
            }
            ac.vigil.monitor.DetectionStatus status = monitor.status(name, vl, alertVl, enabled);
            String stateColor = switch (status.state()) {
                case VIOLATION -> "&c";
                case SUSPECT -> "&e";
                case EXEMPT -> "&b";
                case DISABLED -> "&8";
                default -> "&a";
            };
            StringBuilder line = new StringBuilder(String.format(java.util.Locale.US,
                    "&7  %s: %s%s &7vl=&e%.2f &7conf=&e%.2f &7corr=&e%s &7evidence=&e%d &7last=&e%s",
                    name, stateColor, status.state(), vl, status.confidence(), status.correlationStatus(), status.evidenceCount(),
                    ageText(now, status.lastDetectionAt())));
            if (status.lastEvidenceAt() > 0L) {
                line.append(" &7ev=&e").append(ageText(now, status.lastEvidenceAt()))
                        .append("&7: ").append(truncate(status.lastEvidenceDetail(), 48));
            }
            if (!status.exemptionReason().isEmpty()) {
                line.append(" &7[exempt: &b").append(status.exemptionReason()).append("&7]");
            }
            sender.sendMessage(Text.colorize(line.toString()));
        }

        java.util.List<ac.vigil.monitor.Evidence> recent = monitor.evidenceSnapshot();
        int shown = 0;
        if (!recent.isEmpty()) {
            sender.sendMessage(Text.colorize("&7  recent evidence (newest last, max 6):"));
            for (int i = recent.size() - 1; i >= 0 && shown < 6; i--) {
                ac.vigil.monitor.Evidence e = recent.get(i);
                String tag = e.check().isEmpty() ? e.type().name() : e.type().name() + "/" + e.check();
                sender.sendMessage(Text.colorize(String.format(java.util.Locale.US,
                        "&7    [%s] %s conf=%.2f src=%s %s",
                        ageText(now, e.timeMillis()), tag, e.confidence(), e.source(),
                        truncate(e.detail(), 64))));
                shown++;
            }
        }
    }

    /**
     * {@code /vigilac ai <player>} — AI-assist report; {@code /vigilac ai status} — module status;
     * {@code /vigilac ai assistant <player>} — assistant narrative; {@code /vigilac ai ask <player> <question...>}.
     * Everything rendered here is advisory: it never writes violation levels and never triggers actions.
     */
    private void ai(CommandSender sender, String[] args) {
        if (!sender.hasPermission("vigilac.ai")) {
            noPerm(sender);
            return;
        }
        AiServices services = plugin.getAiServices();
        if (services == null) {
            sender.sendMessage(Text.colorize("&eAI module is not initialised (plugin reloading?)."));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(Text.colorize("&cUsage: /vigilac ai <player|status|assistant <player>|ask <player> <question...>>"));
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "status" -> aiStatus(sender);
            case "assistant" -> aiAssistant(sender, args, services);
            case "ask" -> aiAsk(sender, args, services);
            default -> aiReport(sender, args, services);
        }
    }

    private void aiReport(CommandSender sender, String[] args, AiServices services) {
        Player target = targetPlayer(sender, args[1]);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Text.colorize("&e" + target.getName() + " is not being tracked yet."));
            return;
        }
        long now = data.monitor().nowMillis();
        String report = services.reportFor(data, target.getName(), now);
        if (report == null) {
            sender.sendMessage(Text.colorize("&eReport unavailable for " + target.getName()));
            return;
        }
        for (String line : report.split("\n")) {
            sender.sendMessage(line);
        }
    }

    private void aiStatus(CommandSender sender) {
        AiServices services = plugin.getAiServices();
        AiStatus status = services.status();
        sender.sendMessage(Text.colorize("&6AI module status"));
        sender.sendMessage(Text.colorize("&7  enabled: &e" + status.enabled()
                + " &7| shadow-only: &e" + status.shadowOnly()
                + " &7| usable: &e" + status.usable()));
        sender.sendMessage(Text.colorize("&7  reason: &e" + (status.unavailableReason() == null ? "none" : status.unavailableReason())));
        if (status.modelStatuses().isEmpty()) {
            sender.sendMessage(Text.colorize("&7  models: &enone (directory empty or all unavailable)"));
        } else {
            sender.sendMessage(Text.colorize("&7  models:"));
            for (String line : status.modelStatuses()) {
                sender.sendMessage(Text.colorize("&7    " + line));
            }
        }
        sender.sendMessage(Text.colorize(String.format(Locale.US,
                "&7  counters: submitted=&e%d dropped=&e%d completed=&e%d failed=&e%d timedOut=&e%d invalid=&e%d latencyEma=&e%.3fms",
                status.submitted(), status.droppedFull(), status.completed(),
                status.failed(), status.timedOut(), status.invalidSnapshots(), status.averageLatencyMillis())));
        sender.sendMessage(Text.colorize("&7  AI output is advisory only — it never writes violation levels, alerts, kicks or bans."));
        sender.sendMessage(Text.colorize("&7  Deterministic checks remain authoritative."));
    }

    private void aiAssistant(CommandSender sender, String[] args, AiServices services) {
        if (args.length < 3) {
            sender.sendMessage(Text.colorize("&cUsage: /vigilac ai assistant <player>"));
            return;
        }
        Player target = targetPlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Text.colorize("&e" + target.getName() + " is not being tracked yet."));
            return;
        }
        long now = data.monitor().nowMillis();
        PlayerProfile profile = services.profileFor(data, target.getName(), now);
        AiAssessment assessment = services.config().enabled() ? services.assessmentFor(data, now) : null;
        AdminAssistant assistant = services.assistantFor(target.getName());
        sender.sendMessage(Text.colorize("&6AI assistant (advisory) — &e" + target.getName()));
        sendLines(sender, assistant.summarizePlayer(profile, assessment));
        sendLines(sender, assistant.explainAssessment(assessment));
        sendLines(sender, assistant.falsePositiveReading(assessment));
        sendLines(sender, assistant.recommendedInvestigation(assessment));
    }

    private void aiAsk(CommandSender sender, String[] args, AiServices services) {
        if (args.length < 4) {
            sender.sendMessage(Text.colorize("&cUsage: /vigilac ai ask <player> <question...>"));
            return;
        }
        Player target = targetPlayer(sender, args[2]);
        if (target == null) {
            return;
        }
        PlayerData data = plugin.getPlayerDataManager().get(target.getUniqueId());
        if (data == null) {
            sender.sendMessage(Text.colorize("&e" + target.getName() + " is not being tracked yet."));
            return;
        }
        String question = String.join(" ", Arrays.copyOfRange(args, 3, args.length));
        long now = data.monitor().nowMillis();
        String report = services.reportFor(data, target.getName(), now);
        AdminAssistant assistant = services.assistantFor(target.getName());
        sendLines(sender, assistant.ask(question, report == null ? "" : report));
    }

    private static void sendLines(CommandSender sender, String text) {
        if (text == null) {
            return;
        }
        for (String line : text.split("\n")) {
            sender.sendMessage(line);
        }
    }

    private Player targetPlayer(CommandSender sender, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            sender.sendMessage(Text.colorize("&cPlayer not found: " + name));
        }
        return target;
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() > max ? value.substring(0, max) + "..." : value;
    }

    private static String ageText(long now, long timestamp) {
        if (timestamp <= 0L) {
            return "never";
        }
        long age = Math.max(0L, now - timestamp);
        if (age < 1_000L) {
            return age + "ms";
        }
        if (age < 60_000L) {
            return (age / 1_000L) + "s";
        }
        return (age / 60_000L) + "m" + ((age % 60_000L) / 1_000L) + "s";
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
        if (args.length == 2 && (sub.equals("check") || sub.equals("violations") || sub.equals("debug") || sub.equals("exempt") || sub.equals("monitor") || sub.equals("ai"))) {
            if (sub.equals("ai")) {
                List<String> options = new ArrayList<>(List.of("status", "assistant", "ask"));
                options.addAll(onlineNames());
                return filter(options, args[1]);
            }
            return filter(onlineNames(), args[1]);
        }
        if (args.length == 3 && sub.equals("ai") && (args[1].equalsIgnoreCase("assistant") || args[1].equalsIgnoreCase("ask"))) {
            return filter(onlineNames(), args[2]);
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
