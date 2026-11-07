package ac.vigil.ai.report;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class TelemetryRedactorTest {

    @Test
    void redactsUuidsAndIpv4AndTargetName() {
        TelemetryRedactor redactor = new TelemetryRedactor(true, "Steve");
        String input = "player Steve (uuid 123e4567-e89b-12d3-a456-426655440000) ping from 192.168.0.14 hit aSteve and Steve again";
        String out = redactor.redact(input);
        assertFalse(out.contains("123e4567-e89b-12d3-a456-426655440000"), "uuid must be redacted");
        assertFalse(out.contains("192.168.0.14"), "IPv4 literal must be redacted");
        assertFalse(out.contains("Steve"), "target name must be redacted (case-insensitive)");
    }

    @Test
    void disabledRedactorPassesThrough() {
        TelemetryRedactor redactor = new TelemetryRedactor(false, "Steve");
        String input = "player Steve 10.0.0.1 123e4567-e89b-12d3-a456-426655440000";
        assertEquals(input, redactor.redact(input));
    }

    @Test
    void fingerprintIsShortStableHex() {
        String fp = TelemetryRedactor.fingerprint("Steve");
        assertEquals(8, fp.length());
        assertTrue(fp.matches("[0-9a-f]{8}"));
        assertEquals(fp, TelemetryRedactor.fingerprint("Steve"));
        assertNotEquals(fp, TelemetryRedactor.fingerprint("Alex"));
    }
}
