package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class CaptureJsonTest {

    @Test
    void escapesQuotesBackslashesAndControlCharacters() {
        String json = CaptureJson.object(Map.of("scenario", "a\"b\\c\nd\te"));
        assertEquals("{\"scenario\":\"a\\\"b\\\\c\\nd\\te\"}", json);
    }

    @Test
    void nonAsciiPassesThroughUtf8() {
        String json = CaptureJson.object(Map.of("scenario", "mùa hè"));
        assertTrue(json.contains("mùa hè"));
    }

    @Test
    void nestedStructuresRoundTripShape() {
        String json = CaptureJson.object(Map.of(
                "features", List.of(1.0, 2.5, 3.0),
                "enabled", Boolean.TRUE));
        assertEquals("{\"features\":[1.0,2.5,3.0],\"enabled\":true}", json);
    }

    @Test
    void nonFiniteDoublesAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CaptureJson.object(Map.of("x", Double.NaN)));
        assertThrows(IllegalArgumentException.class,
                () -> CaptureJson.object(Map.of("x", Double.POSITIVE_INFINITY)));
    }
}
