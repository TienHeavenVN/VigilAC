package ac.vigil.capture;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Per-player capture consent store. Stores ONLY opaque HMAC tokens — never a
 * UUID, username or address — in {@code <dataDir>/capture/consent.json}:
 *
 * <pre>{"version":1,"tokens":["0123...","abcd..."]}</pre>
 *
 * Writes are atomic (tmp file + move). A corrupt store is never silently
 * discarded: it is renamed to {@code consent.json.corrupt-<epoch>} and capture
 * starts from an EMPTY set (fail closed — no player is captured without a token).
 */
public final class CaptureConsent {

    private static final Pattern TOKEN = Pattern.compile("\"([0-9a-f]{32})\"");
    private static final String MAGIC = "\"version\":1";

    private final Path storeFile;
    private final CapturePseudonymizer pseudonymizer;
    private final Set<String> tokens = new LinkedHashSet<>();

    public CaptureConsent(Path dataDir, CapturePseudonymizer pseudonymizer) {
        this.storeFile = dataDir.resolve("capture").resolve("consent.json");
        this.pseudonymizer = pseudonymizer;
    }

    /** (Re)loads the store from disk. Missing file ⇒ empty set. */
    public synchronized void load() {
        tokens.clear();
        if (!Files.exists(storeFile)) {
            return;
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (!json.contains(MAGIC)) {
                throw new IOException("unrecognised consent store format");
            }
            Matcher matcher = TOKEN.matcher(json);
            while (matcher.find()) {
                tokens.add(matcher.group(1));
            }
        } catch (IOException | RuntimeException e) {
            quarantine(e.toString());
        }
    }

    /** Grants consent for the given player; persists atomically. */
    public synchronized void grant(UUID player) {
        if (tokens.add(pseudonymizer.consentToken(player))) {
            persist();
        }
    }

    /** Revokes consent for the given player; persists atomically. */
    public synchronized void revoke(UUID player) {
        if (tokens.remove(pseudonymizer.consentToken(player))) {
            persist();
        }
    }

    public synchronized boolean hasConsent(UUID player) {
        return tokens.contains(pseudonymizer.consentToken(player));
    }

    /** Deletes the whole consent store (admin "delete everything" path). */
    public synchronized void deleteAll() {
        tokens.clear();
        try {
            Files.deleteIfExists(storeFile);
        } catch (IOException e) {
            throw new UncheckedIOException("could not delete consent store " + storeFile, e);
        }
    }

    public synchronized int count() {
        return tokens.size();
    }

    public Path storeFile() {
        return storeFile;
    }

    private void persist() {
        Path dir = storeFile.getParent();
        try {
            Files.createDirectories(dir);
            StringBuilder json = new StringBuilder("{\"version\":1,\"tokens\":[");
            boolean first = true;
            for (String token : tokens) {
                if (!first) {
                    json.append(',');
                }
                first = false;
                json.append('"').append(token).append('"');
            }
            json.append("]}\n");
            Path tmp = dir.resolve("consent.json.tmp");
            Files.writeString(tmp, json.toString(), StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.move(tmp, storeFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            throw new UncheckedIOException("could not persist consent store " + storeFile, e);
        }
    }

    private void quarantine(String reason) {
        tokens.clear();
        Path quarantine = storeFile.resolveSibling("consent.json.corrupt-" + System.currentTimeMillis());
        try {
            Files.move(storeFile, quarantine, StandardCopyOption.REPLACE_EXISTING);
            System.getLogger("ac.vigil.capture").log(System.Logger.Level.WARNING,
                    "Capture consent store was unreadable ({0}); moved to {1} and starting from an EMPTY set (fail closed).",
                    reason, quarantine);
        } catch (IOException moveFailure) {
            System.getLogger("ac.vigil.capture").log(System.Logger.Level.WARNING,
                    "Capture consent store was unreadable ({0}) and could not be quarantined ({1}); using an EMPTY set in memory (fail closed). File left untouched.",
                    reason, moveFailure);
        }
    }
}
