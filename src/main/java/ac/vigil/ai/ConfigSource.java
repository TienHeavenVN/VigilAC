package ac.vigil.ai;

import java.util.List;

/**
 * Minimal read view over the plugin configuration so the AI module never
 * touches Bukkit types in tests. The production implementation
 * ({@link BukkitConfigSource}) reads the live FileConfiguration; tests use an
 * in-memory map.
 */
public interface ConfigSource {

    String getString(String path, String defaultValue);

    int getInt(String path, int defaultValue);

    double getDouble(String path, double defaultValue);

    boolean getBoolean(String path, boolean defaultValue);

    List<String> getStringList(String path, List<String> defaultValue);
}
