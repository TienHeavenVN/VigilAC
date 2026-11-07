package ac.vigil.capture;

import java.util.List;
import java.util.Map;

/**
 * Minimal JSON writer for capture rows. Only what the pipeline needs: strings,
 * numbers, booleans, string/number arrays and flat objects. No escaping games:
 * control characters and quotes are escaped, non-ASCII passes through (writer is
 * UTF-8). Not a general-purpose JSON library — do not grow one here.
 */
final class CaptureJson {

    private CaptureJson() {
    }

    /** Serializes one flat object preserving iteration order. */
    static String object(Map<String, Object> fields) {
        StringBuilder out = new StringBuilder(512);
        out.append('{');
        boolean first = true;
        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            if (!first) {
                out.append(',');
            }
            first = false;
            appendString(out, entry.getKey());
            out.append(':');
            value(out, entry.getValue());
        }
        out.append('}');
        return out.toString();
    }

    @SuppressWarnings("unchecked")
    private static void value(StringBuilder out, Object value) {
        if (value == null) {
            out.append("null");
            return;
        }
        if (value instanceof String s) {
            appendString(out, s);
            return;
        }
        if (value instanceof Double d) {
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number in capture row");
            }
            out.append(Double.toString(d));
            return;
        }
        if (value instanceof Float f) {
            double d = f.doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                throw new IllegalArgumentException("non-finite number in capture row");
            }
            out.append(Double.toString(d));
            return;
        }
        if (value instanceof Number n) {
            out.append(n.toString());
            return;
        }
        if (value instanceof Boolean b) {
            out.append(b);
            return;
        }
        if (value instanceof List<?> list) {
            out.append('[');
            boolean first = true;
            for (Object element : list) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                value(out, element);
            }
            out.append(']');
            return;
        }
        if (value instanceof Map<?, ?> map) {
            out.append(object((Map<String, Object>) map));
            return;
        }
        throw new IllegalArgumentException("unsupported JSON value type: " + value.getClass().getName());
    }

    static void appendString(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }
}
