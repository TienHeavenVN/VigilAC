package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CaptureConsentTest {

    @TempDir
    Path dir;

    private CapturePseudonymizer pseudonymizer() {
        return new CapturePseudonymizer("key-key-key-key-key-key-key-key-12".getBytes(java.nio.charset.StandardCharsets.UTF_8), 24);
    }

    @Test
    void grantRevokePersistsAcrossReload() {
        UUID player = UUID.randomUUID();
        CaptureConsent consent = new CaptureConsent(dir, pseudonymizer());
        consent.load();
        assertFalse(consent.hasConsent(player));
        consent.grant(player);
        assertTrue(consent.hasConsent(player));

        // reload from disk (new instance)
        CaptureConsent reloaded = new CaptureConsent(dir, pseudonymizer());
        reloaded.load();
        assertTrue(reloaded.hasConsent(player));
        assertEquals(1, reloaded.count());

        reloaded.revoke(player);
        CaptureConsent afterRevoke = new CaptureConsent(dir, pseudonymizer());
        afterRevoke.load();
        assertFalse(afterRevoke.hasConsent(player));
        assertEquals(0, afterRevoke.count());
    }

    @Test
    void storedFileContainsOnlyOpaqueTokens() throws Exception {
        UUID player = UUID.fromString("deadbeef-1234-5678-9abc-def012345678");
        CaptureConsent consent = new CaptureConsent(dir, pseudonymizer());
        consent.load();
        consent.grant(player);
        String json = Files.readString(consent.storeFile());
        assertFalse(json.contains(player.toString()), "consent store must not contain the UUID");
        assertFalse(json.contains("deadbeef"), "consent store must not contain UUID fragments");
        assertTrue(json.matches("(?s).*[0-9a-f]{32}.*"), "store holds opaque hex tokens");
    }

    @Test
    void corruptStoreFailsClosedAndIsQuarantined() throws Exception {
        UUID player = UUID.randomUUID();
        Path store = dir.resolve("capture").resolve("consent.json");
        Files.createDirectories(store.getParent());
        Files.writeString(store, "{not json at all");
        CaptureConsent consent = new CaptureConsent(dir, pseudonymizer());
        consent.load();
        assertFalse(consent.hasConsent(player), "corrupt store must fail closed");
        assertEquals(0, consent.count());
        try (var files = Files.list(dir.resolve("capture"))) {
            assertTrue(files.anyMatch(f -> f.getFileName().toString().startsWith("consent.json.corrupt-")),
                    "corrupt store must be quarantined, not silently discarded");
        }
    }

    @Test
    void deleteAllRemovesStoreAndConsent() throws Exception {
        UUID player = UUID.randomUUID();
        CaptureConsent consent = new CaptureConsent(dir, pseudonymizer());
        consent.load();
        consent.grant(player);
        assertTrue(Files.exists(consent.storeFile()));
        consent.deleteAll();
        assertFalse(Files.exists(consent.storeFile()));
        assertEquals(0, consent.count());
    }
}
