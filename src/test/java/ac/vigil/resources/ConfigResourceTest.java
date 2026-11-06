package ac.vigil.resources;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

/**
 * Guards the shipped {@code config.yml} and {@code plugin.yml}: they must
 * parse and must contain every key the code reads. This is a packaging
 * contract test, not a semantics test.
 */
class ConfigResourceTest {

    private Map<String, Object> loadYaml(String path) throws Exception {
        try (InputStream in = Files.newInputStream(Path.of(path))) {
            Object loaded = new Yaml().load(in);
            assertNotNull(loaded, path + " must parse");
            return castMap(loaded);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        return (Map<String, Object>) o;
    }

    @Test
    void configYmlParsesAndContainsRequiredKeys() throws Exception {
        Map<String, Object> root = loadYaml("src/main/resources/config.yml");

        assertEquals("alert-only", root.get("mode"), "default mode must be alert-only");

        Map<String, Object> checks = castMap(root.get("checks"));
        assertNotNull(checks, "checks section required");

        Map<String, Object> speed = castMap(checks.get("speed"));
        assertNotNull(speed);
        for (String key : List.of("enabled", "alert-vl", "warn-vl", "kick-vl", "max-vl",
                "walk-cap", "sprint-cap", "speed-potion-bonus", "slippery-multiplier",
                "excess-vl-per-second", "min-sustain-seconds")) {
            assertTrue(speed.containsKey(key), "checks.speed." + key + " is required by the code");
        }

        Map<String, Object> reach = castMap(checks.get("reach"));
        assertNotNull(reach);
        for (String key : List.of("enabled", "alert-vl", "warn-vl", "kick-vl", "max-vl",
                "max-reach", "tolerance", "min-attacks-over", "vl-per-attack-over", "ema-samples")) {
            assertTrue(reach.containsKey(key), "checks.reach." + key + " is required by the code");
        }

        Map<String, Object> violations = castMap(root.get("violations"));
        assertTrue(violations.containsKey("decay-per-second"));
        assertTrue(violations.containsKey("decay-delay-seconds"));

        Map<String, Object> storage = castMap(root.get("storage"));
        assertEquals(Boolean.TRUE, storage.get("enabled"));
        assertEquals("vigilac-violations.db", storage.get("file"));

        Map<String, Object> punishments = castMap(root.get("punishments"));
        assertTrue(punishments.containsKey("commands-on-warn"));
        assertTrue(punishments.containsKey("commands-on-kick"));

        Map<String, Object> confounders = castMap(root.get("confounders"));
        assertTrue(confounders.containsKey("teleport-threshold"));
        assertTrue(confounders.containsKey("teleport-grace-seconds"));
        assertTrue(confounders.containsKey("velocity-grace-millis"));

        assertEquals(Boolean.FALSE, root.get("debug"));
        assertEquals("vigilac.exempt", root.get("exempt-permission"));
    }

    @Test
    void pluginYmlDeclaresVigilacCommandAndMainClass() throws Exception {
        String text = Files.readString(Path.of("src/main/resources/plugin.yml"));
        assertTrue(text.contains("main: ac.vigil.VigilAC"), "plugin.yml main class");
        assertTrue(text.contains("api-version: '1.21'"), "plugin.yml api-version");
        assertTrue(text.contains("vigilac:"), "plugin.yml declares the vigilac command");
        assertTrue(text.contains("permission: vigilac.command"), "plugin.yml wires the base permission");
        for (String perm : List.of("vigilac.alerts", "vigilac.check", "vigilac.violations",
                "vigilac.debug", "vigilac.reload", "vigilac.exempt", "vigilac.info")) {
            assertTrue(text.contains(perm + ":"), "plugin.yml must declare permission " + perm);
        }
    }
}
