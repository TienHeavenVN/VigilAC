package ac.vigil.ai;

import java.util.List;
import org.bukkit.configuration.file.FileConfiguration;

/** Production {@link ConfigSource} backed by the live plugin configuration. */
public final class BukkitConfigSource implements ConfigSource {

    private final FileConfiguration configuration;

    public BukkitConfigSource(FileConfiguration configuration) {
        this.configuration = configuration;
    }

    @Override
    public String getString(String path, String defaultValue) {
        String value = configuration.getString(path);
        return value == null ? defaultValue : value;
    }

    @Override
    public int getInt(String path, int defaultValue) {
        return configuration.getInt(path, defaultValue);
    }

    @Override
    public double getDouble(String path, double defaultValue) {
        return configuration.getDouble(path, defaultValue);
    }

    @Override
    public boolean getBoolean(String path, boolean defaultValue) {
        return configuration.getBoolean(path, defaultValue);
    }

    @Override
    public List<String> getStringList(String path, List<String> defaultValue) {
        List<String> value = configuration.getStringList(path);
        return value.isEmpty() ? defaultValue : value;
    }
}
