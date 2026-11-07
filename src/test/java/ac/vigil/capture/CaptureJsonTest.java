package ac.vigil.capture;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
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
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("features", List.of(1.0, 2.5, 3.0));
        fields.put("enabled", Boolean.TRUE);
        String json = CaptureJson.object(fields);
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
