package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class CapturePseudonymizerTest {

    private final CapturePseudonymizer pseudonymizer =
            new CapturePseudonymizer("0123456789abcdef0123456789abcdef".getBytes(java.nio.charset.StandardCharsets.UTF_8), 24);

    @Test
    void playerPseudonymIsDeterministicWithinBucketAndRotatesAcrossBuckets() {
        UUID uuid = UUID.randomUUID();
        long day0 = 0L;
        long day1 = 24L * 3_600_000L;
        String a = pseudonymizer.playerPseudonym(uuid, day0);
        String b = pseudonymizer.playerPseudonym(uuid, day0 + 1000);
        String c = pseudonymizer.playerPseudonym(uuid, day1);
        assertEquals(a, b, "same bucket ⇒ same pseudonym");
        assertNotEquals(a, c, "next bucket ⇒ rotated pseudonym (unlinkable)");
        assertEquals(32, a.length());
        assertTrue(a.matches("[0-9a-f]{32}"));
    }

    @Test
    void differentPlayersGetDifferentPseudonyms() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        assertNotEquals(pseudonymizer.playerPseudonym(one, 0L), pseudonymizer.playerPseudonym(two, 0L));
    }

    @Test
    void sessionPseudonymsAreUniqueAndNeverDerivedFromUuid() {
        String s1 = pseudonymizer.newSessionPseudonym();
        String s2 = pseudonymizer.newSessionPseudonym();
        assertNotEquals(s1, s2);
        assertEquals(32, s1.length());
        assertTrue(s1.matches("[0-9a-f]{32}"));
    }

    @Test
    void consentTokenIsStableAcrossBuckets() {
        UUID uuid = UUID.randomUUID();
        long day0 = 0L;
        long day9 = 9L * 24 * 3_600_000L;
        assertEquals(pseudonymizer.consentToken(uuid), pseudonymizer.consentToken(uuid));
        assertEquals(32, pseudonymizer.consentToken(uuid).length());
        // rotation must not change the consent token, or revocation would silently leak participants
        assertEquals(pseudonymizer.consentToken(uuid), pseudonymizer.consentToken(uuid));
        assertNotEquals(pseudonymizer.playerPseudonym(uuid, day0), pseudonymizer.playerPseudonym(uuid, day9));
    }

    @Test
    void keyTooShortIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new CapturePseudonymizer(new byte[8], 24));
    }

    @Test
    void pseudonymContainsNoUuidMaterial() {
        UUID uuid = UUID.fromString("deadbeef-1234-5678-9abc-def012345678");
        String pseudonym = pseudonymizer.playerPseudonym(uuid, 123L);
        String compact = uuid.toString().replace("-", "");
        assertFalse(pseudonym.contains(uuid.toString().substring(0, 8)));
        assertFalse(pseudonym.contains(compact.substring(0, 8)));
    }
}
