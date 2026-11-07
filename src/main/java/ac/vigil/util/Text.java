package ac.vigil.util;

import org.bukkit.ChatColor;

/** Chat formatting helpers shared by commands and messages. */
public final class Text {

    private Text() {
    }

    /** Translates legacy {@code &} color codes to section signs. */
    public static String colorize(String input) {
        return input == null ? "" : ChatColor.translateAlternateColorCodes('&', input);
    }

    /** Formats a double with a fixed precision for command output. */
    public static String fmt(double value) {
        return String.format(java.util.Locale.US, "%.2f", value);
    }
}
