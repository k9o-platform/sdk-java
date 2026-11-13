package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The Kilimanjaro JCS code-point profile, implemented without a dependency.
 *
 * <p>Object keys are sorted by UTF-8 byte order (the same as Unicode code-point order), there is no whitespace,
 * strings are escaped minimally ({@code "} and {@code \} with a backslash, the short escapes for backspace, tab, newline, form feed and carriage return,
 * every other control character below U+0020 as a lowercase four-digit hex escape; {@code /} and every other code
 * point are written literally as UTF-8). Numbers are integers only: base 10, no exponent, {@code -0} written {@code 0}. A
 * fractional number, {@code NaN}, an infinity, or a magnitude above 9007199254740991 (2^53 - 1) is rejected with
 * {@link IllegalArgumentException}, as is a string with an unpaired surrogate.
 *
 * <p>Accepts {@code Map}, {@code Collection}, {@code Object[]}, {@code String}, {@code Boolean}, {@code null}, any
 * {@code Number} and a Jackson {@code JsonNode}. Other objects are converted through Jackson first.
 */
public final class Jcs {

    private Jcs() {}

    /** The canonical form as text. */
    public static String canonicalize(Object value) {
        StringBuilder sb = new StringBuilder();
        write(sb, value, 0);
        return sb.toString();
    }

    /** The canonical form as UTF-8 bytes. */
    public static byte[] canonicalBytes(Object value) {
        return canonicalize(value).getBytes(StandardCharsets.UTF_8);
    }

    private static final int MAX_DEPTH = 256;

    private static void write(StringBuilder sb, Object v, int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting is too deep to canonicalize");
        if (v == null) { sb.append("null"); return; }
        if (v instanceof JsonNode n) {
            try {
                write(sb, HttpTransport.JSON.treeToValue(n, Object.class), depth);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new IllegalArgumentException("cannot canonicalize JSON: " + e.getMessage(), e);
            }
            return;
        }
        if (v instanceof String s) { writeString(sb, s); return; }
        if (v instanceof Boolean b) { sb.append(b ? "true" : "false"); return; }
        if (v instanceof Number n) { sb.append(numberToString(n)); return; }
        if (v instanceof Map<?, ?> m) {
            List<String> keys = new ArrayList<>(m.size());
            for (Object k : m.keySet()) {
                if (!(k instanceof String ks)) throw new IllegalArgumentException("object keys must be strings");
                keys.add(ks);
            }
            keys.sort(Jcs::compareCodePoints);
            sb.append('{');
            boolean first = true;
            for (String k : keys) {
                if (!first) sb.append(',');
                first = false;
                writeString(sb, k);
                sb.append(':');
                write(sb, m.get(k), depth + 1);
            }
            sb.append('}');
            return;
        }
        if (v instanceof Collection<?> c) {
            sb.append('[');
            boolean first = true;
            for (Object e : c) {
                if (!first) sb.append(',');
                first = false;
                write(sb, e, depth + 1);
            }
            sb.append(']');
            return;
        }
        if (v instanceof Object[] arr) {
            write(sb, java.util.Arrays.asList(arr), depth);
            return;
        }
        // Records and other beans: let Jackson turn them into maps, lists and scalars.
        Object converted = HttpTransport.JSON.convertValue(v, Object.class);
        if (converted == null || converted.getClass() == v.getClass()) {
            throw new IllegalArgumentException("cannot canonicalize a " + v.getClass().getName());
        }
        write(sb, converted, depth);
    }

    private static void writeString(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\t' -> sb.append("\\t");
                case '\n' -> sb.append("\\n");
                case '\f' -> sb.append("\\f");
                case '\r' -> sb.append("\\r");
                default -> {
                    if (c < 0x20) {
                        sb.append("\\u00").append(Character.forDigit(c >> 4, 16)).append(Character.forDigit(c & 15, 16));
                    } else if (Character.isHighSurrogate(c)) {
                        if (i + 1 >= s.length() || !Character.isLowSurrogate(s.charAt(i + 1))) {
                            throw new IllegalArgumentException("string has an unpaired surrogate");
                        }
                        sb.append(c).append(s.charAt(++i));
                    } else if (Character.isLowSurrogate(c)) {
                        throw new IllegalArgumentException("string has an unpaired surrogate");
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    private static final long MAX_SAFE = 9_007_199_254_740_991L;
    private static final BigInteger MAX_SAFE_BIG = BigInteger.valueOf(MAX_SAFE);

    /** Orders strings by code point, which is the order of their UTF-8 bytes. */
    static int compareCodePoints(String a, String b) {
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            int ca = a.codePointAt(i), cb = b.codePointAt(j);
            if (ca != cb) return Integer.compare(ca, cb);
            i += Character.charCount(ca);
            j += Character.charCount(cb);
        }
        return Integer.compare(a.length() - i, b.length() - j);
    }

    private static String numberToString(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte
            || n instanceof java.util.concurrent.atomic.AtomicInteger || n instanceof java.util.concurrent.atomic.AtomicLong) {
            return safeLong(n.longValue());
        }
        if (n instanceof BigInteger bi) return safeBig(bi);
        if (n instanceof BigDecimal bd) {
            try {
                return safeBig(bd.toBigIntegerExact());
            } catch (ArithmeticException e) {
                throw new IllegalArgumentException("a non-integer number cannot be canonicalized");
            }
        }
        double d = n.doubleValue();
        if (Double.isNaN(d) || Double.isInfinite(d)) throw new IllegalArgumentException("NaN and Infinity cannot be canonicalized");
        if (d != Math.rint(d)) throw new IllegalArgumentException("a non-integer number cannot be canonicalized");
        if (Math.abs(d) > MAX_SAFE) throw new IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized");
        return Long.toString((long) d);
    }

    private static String safeLong(long l) {
        if (l > MAX_SAFE || l < -MAX_SAFE) throw new IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized");
        return Long.toString(l);
    }

    private static String safeBig(BigInteger bi) {
        if (bi.abs().compareTo(MAX_SAFE_BIG) > 0) throw new IllegalArgumentException("an integer above 2^53-1 cannot be canonicalized");
        return bi.toString();
    }
}
