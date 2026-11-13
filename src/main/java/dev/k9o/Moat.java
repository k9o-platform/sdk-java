package dev.k9o;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Moat: keeps the system prompt yours, and checks what goes in and what comes out. It runs in your process, with no
 * account and no network access.
 *
 * <p>What it does, all locally:
 * <ol>
 *   <li>Pins the system prompt. {@link #messages(Object)} builds the message list itself: your prompt is the only
 *       {@code system} message, and untrusted input is always a {@code user} message. Input that carries a role or
 *       system marker is refused.</li>
 *   <li>Enforces an input schema before the input is placed in a message.</li>
 *   <li>Enforces an output schema on what the model returns.</li>
 *   <li>Passes the output through a PII filter you supply, when you supply one.</li>
 * </ol>
 *
 * <p>The pin is structural. It stops injected text from becoming the system prompt; it does not stop that text from
 * reaching the model, and the model may still follow it. Moat is one layer, not a guarantee.
 *
 * <p>A refusal is a {@link Refusal} (unchecked) with a {@link Reason} and a short {@code detail} that never repeats
 * the content. Schemas use a small JSON Schema subset (see {@link Options.Builder#inputSchema}); a keyword outside the
 * subset is a {@link SchemaError} at construction, so a typo never silently turns a check off.
 */
public final class Moat {

    /** Why Moat refused. The set is closed. */
    public enum Reason {
        ROLE_MARKER("role_marker"), INPUT_SCHEMA("input_schema"), OUTPUT_SCHEMA("output_schema"), PII("pii");

        private final String wire;

        Reason(String wire) { this.wire = wire; }

        /** The lowercase name, for example {@code role_marker}. */
        public String wire() { return wire; }
    }

    /** An expected refusal of input or output. */
    public static final class Refusal extends RuntimeException {
        private final Reason reason;
        private final String detail;

        public Refusal(Reason reason, String detail) {
            super(reason.wire() + ": " + detail);
            this.reason = reason;
            this.detail = detail;
        }

        public Reason reason() { return reason; }

        public String detail() { return detail; }
    }

    /** A schema that uses something outside the supported subset, or a malformed value for a supported keyword. */
    public static final class SchemaError extends IllegalArgumentException {
        public SchemaError(String message) { super(message); }
    }

    /** One chat message. */
    public record Message(String role, String content) {}

    /**
     * Looks at model output and says whether it holds personal data. Return {@code null} (or an empty string) when the
     * text is clean, or a short description of what was found to refuse the output. The text is passed to the filter
     * as it is; do not return the matched text itself.
     *
     * <p>Moat does not depend on any other package. A Barbican instance fits as
     * {@code text -> "allow".equals(barbican.inspect(text).decision()) ? null : "flagged"}.
     */
    @FunctionalInterface
    public interface PiiFilter {
        String detect(String text);
    }

    /** Settings of a {@link Moat}. */
    public static final class Options {
        final String prompt;
        final Map<String, Object> inputSchema;
        final Map<String, Object> outputSchema;
        final PiiFilter pii;

        private Options(Builder b) {
            this.prompt = b.prompt;
            this.inputSchema = b.inputSchema;
            this.outputSchema = b.outputSchema;
            this.pii = b.pii;
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private String prompt;
            private Map<String, Object> inputSchema;
            private Map<String, Object> outputSchema;
            private PiiFilter pii;

            /** The pinned system prompt. Required. */
            public Builder prompt(String v) { this.prompt = v; return this; }

            /**
             * Schema the untrusted input must satisfy. Supported keywords: {@code type} (object, array, string, number,
             * integer, boolean, null, or a list of them), {@code properties}, {@code required},
             * {@code additionalProperties} (true or false), {@code items} (one schema), {@code enum}, {@code const},
             * {@code minLength}, {@code maxLength}, {@code pattern} (searched, not anchored), {@code minimum},
             * {@code maximum}, {@code minItems}, {@code maxItems}.
             */
            public Builder inputSchema(Map<String, Object> v) { this.inputSchema = v; return this; }

            /** Schema the model output must satisfy; same keywords as the input schema. */
            public Builder outputSchema(Map<String, Object> v) { this.outputSchema = v; return this; }

            /** The PII filter applied to the output after the schema check. Without one there is no PII step. */
            public Builder pii(PiiFilter v) { this.pii = v; return this; }

            public Options build() { return new Options(this); }
        }
    }

    private final String prompt;
    private final Map<String, Object> inputSchema;
    private final Map<String, Object> outputSchema;
    private final PiiFilter pii;
    private final Map<String, Pattern> patterns = new java.util.HashMap<>();

    /** Build a Moat. Throws {@link IllegalArgumentException} without a prompt and {@link SchemaError} for a bad schema. */
    public Moat(Options options) {
        if (options == null || options.prompt == null || options.prompt.isEmpty()) {
            throw new IllegalArgumentException("a Moat needs a prompt");
        }
        this.prompt = options.prompt;
        this.inputSchema = options.inputSchema;
        this.outputSchema = options.outputSchema;
        this.pii = options.pii;
        if (inputSchema != null) checkSchema(inputSchema, "inputSchema");
        if (outputSchema != null) checkSchema(outputSchema, "outputSchema");
    }

    // ---------------------------------------------------------------- messages

    /**
     * The message list for one call: your prompt as the {@code system} message, then the untrusted input as the
     * {@code user} message (a string as it is, anything else as canonical JSON). Throws a {@link Refusal} with
     * {@code input_schema} or {@code role_marker}.
     */
    public List<Message> messages(Object untrustedInput) {
        Object value = toJson(untrustedInput);
        if (inputSchema != null) {
            String err = validate(value, inputSchema, "$");
            if (err != null) throw new Refusal(Reason.INPUT_SCHEMA, err);
        }
        String where = findRoleMarker(value, 0);
        if (where != null) throw new Refusal(Reason.ROLE_MARKER, where);
        String text;
        if (value instanceof String s) {
            text = s;
        } else {
            try {
                text = Jcs.canonicalize(value);
            } catch (IllegalArgumentException e) {
                // The receipt profile is integers only; the model still gets fractional numbers as plain compact JSON.
                try {
                    text = HttpTransport.JSON.writeValueAsString(value);
                } catch (JsonProcessingException e2) {
                    throw new Refusal(Reason.INPUT_SCHEMA, "the input is not JSON");
                }
            }
        }
        return List.of(new Message("system", prompt), new Message("user", text));
    }

    /** Lowercase hex SHA-256 of the pinned prompt, for example to pass to Rampart. */
    public String promptSha256() {
        try {
            return Rampart.hex(MessageDigest.getInstance("SHA-256").digest(prompt.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------ output

    /**
     * Validate and filter the model output. With an output schema whose type is not exactly {@code string}, the text is
     * parsed as JSON first (a parse failure is {@code output_schema}) and the parsed value is returned; otherwise the
     * text is returned as it is. Throws a {@link Refusal} with {@code output_schema} or {@code pii}.
     */
    public Object checkOutput(String modelOutput) {
        if (modelOutput == null) throw new Refusal(Reason.OUTPUT_SCHEMA, "no output");
        Object value = modelOutput;
        if (outputSchema != null) {
            if (!"string".equals(outputSchema.get("type"))) {
                try {
                    value = toJson(HttpTransport.JSON.readTree(modelOutput));
                } catch (JsonProcessingException e) {
                    throw new Refusal(Reason.OUTPUT_SCHEMA, "the output is not valid JSON");
                }
            }
            String err = validate(value, outputSchema, "$");
            if (err != null) throw new Refusal(Reason.OUTPUT_SCHEMA, err);
        }
        if (pii != null) {
            // The filter must see what the caller receives. A parsed value is written back as compact JSON, so a JSON
            // escape in the raw text cannot hide what the filter looks for; the raw text is checked as well.
            String found = pii.detect(modelOutput);
            if ((found == null || found.isEmpty()) && value != modelOutput) {
                String compact;
                try {
                    compact = HttpTransport.JSON.writeValueAsString(value);
                } catch (JsonProcessingException e) {
                    throw new Refusal(Reason.PII, "the output cannot be written back for the PII filter");
                }
                found = pii.detect(compact);
            }
            if (found != null && !found.isEmpty()) throw new Refusal(Reason.PII, found);
        }
        return value;
    }

    // ----------------------------------------------------------- role markers

    private static final Pattern LINE_MARKER = Pattern.compile("(?m)^[ \\t]*(system|assistant|developer|tool|user)[ \\t]*:");
    private static final Pattern HEADING_MARKER = Pattern.compile("###[ \\t]*(system|instruction)");
    private static final Pattern ROLE_JSON = Pattern.compile("\"role\"\\s*:\\s*\"(system|developer|assistant)\"");
    private static final List<String> TOKEN_MARKERS = List.of("<|system|>", "<|im_start|>", "<|im_end|>", "[inst]", "<<sys>>");
    private static final Set<String> BAD_ROLES = Set.of("system", "developer", "assistant");

    /** Default-ignorable and format code points removed before matching: inclusive {first, last} pairs. */
    private static final int[][] IGNORABLE = {
        {0x00AD, 0x00AD}, {0x034F, 0x034F}, {0x061C, 0x061C}, {0x115F, 0x1160}, {0x17B4, 0x17B5}, {0x180B, 0x180F},
        {0x200B, 0x200F}, {0x202A, 0x202E}, {0x2060, 0x206F}, {0x3164, 0x3164}, {0xFE00, 0xFE0F}, {0xFEFF, 0xFEFF},
        {0xFFA0, 0xFFA0}, {0xFFF0, 0xFFF8}, {0x1BCA0, 0x1BCA3}, {0x1D173, 0x1D17A}, {0xE0000, 0xE0FFF},
    };

    private static boolean ignorable(int cp) {
        for (int[] r : IGNORABLE) if (cp >= r[0] && cp <= r[1]) return true;
        return false;
    }

    private static Map.Entry<Integer, Character> e(int cp, char latin) { return Map.entry(cp, latin); }

    /**
     * Cyrillic and Greek look-alikes of the marker letters, read as the Latin letter. Applied to the comparison text
     * only. A small fixed set: this is not general homoglyph detection. Mirrors moat-confusables.json (version 2).
     */
    static final Map<Integer, Character> CONFUSABLES = Map.ofEntries(
        e(0x0430, 'a'), e(0x0410, 'a'), e(0x0441, 'c'), e(0x0421, 'c'),
        e(0x0435, 'e'), e(0x0415, 'e'), e(0x0456, 'i'), e(0x0406, 'i'),
        e(0x043E, 'o'), e(0x041E, 'o'), e(0x0440, 'p'), e(0x0420, 'p'),
        e(0x0455, 's'), e(0x0405, 's'), e(0x0443, 'y'), e(0x0423, 'y'),
        e(0x0501, 'd'), e(0x04CF, 'l'), e(0x04C0, 'l'), e(0x0422, 't'),
        e(0x041C, 'm'), e(0x041D, 'h'), e(0x0445, 'x'), e(0x0425, 'x'),
        e(0x0391, 'a'), e(0x03B1, 'a'), e(0x0395, 'e'), e(0x03B5, 'e'),
        e(0x0399, 'i'), e(0x03B9, 'i'), e(0x039F, 'o'), e(0x03BF, 'o'),
        e(0x03A1, 'p'), e(0x03C1, 'p'), e(0x03A4, 't'), e(0x03C4, 't'),
        e(0x03A5, 'y'), e(0x03C5, 'u'), e(0x03BD, 'v'), e(0x039C, 'm'),
        e(0x039D, 'n'), e(0x03A7, 'x'), e(0x03C7, 'x'), e(0x03F2, 'c'),
        e(0x03F9, 'c'), e(0x0397, 'h'), e(0x0442, 't')
    );

    /**
     * For matching only: NFKC, then NFKD with nonspacing and enclosing marks dropped, ignorables removed, look-alike
     * letters mapped to Latin, case-folded, runs of horizontal whitespace collapsed to one space.
     */
    static String normalize(String s) {
        String n = Normalizer.normalize(Normalizer.normalize(s, Normalizer.Form.NFKC), Normalizer.Form.NFKD);
        StringBuilder sb = new StringBuilder(n.length());
        boolean inSpace = false;
        for (int i = 0; i < n.length(); ) {
            int cp = n.codePointAt(i);
            i += Character.charCount(cp);
            int type = Character.getType(cp);
            if (type == Character.NON_SPACING_MARK || type == Character.ENCLOSING_MARK) continue;
            if (ignorable(cp)) continue;
            if (cp == '\t' || type == Character.SPACE_SEPARATOR) {
                if (!inSpace) sb.append(' ');
                inSpace = true;
                continue;
            }
            inSpace = false;
            Character latin = CONFUSABLES.get(cp);
            sb.appendCodePoint(latin != null ? latin : cp);
        }
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static String findRoleMarker(Object v, int depth) {
        if (depth > 64) return "the input is nested too deeply";
        if (v instanceof String s) {
            String t = normalize(s);
            if (LINE_MARKER.matcher(t).find()) return "a line starts with a role label";
            if (HEADING_MARKER.matcher(t).find()) return "the text contains a system heading";
            for (String m : TOKEN_MARKERS) if (t.contains(m)) return "the text contains a chat-template marker";
            if (ROLE_JSON.matcher(t).find()) return "the text contains a role key set to a privileged role";
            String trimmed = t.strip();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    String inner = findRoleMarker(toJson(HttpTransport.JSON.readTree(s)), depth + 1);
                    if (inner != null) return inner;
                } catch (JsonProcessingException ignored) {
                    // not JSON: the checks above already looked at the text
                }
            }
            return null;
        }
        if (v instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String key = String.valueOf(e.getKey());
                if (normalize(key).equals("role") && e.getValue() instanceof String rv && BAD_ROLES.contains(normalize(rv).strip())) {
                    return "an object has a role key set to a privileged role";
                }
                String inKey = findRoleMarker(key, depth + 1);
                if (inKey != null) return inKey;
                String inVal = findRoleMarker(e.getValue(), depth + 1);
                if (inVal != null) return inVal;
            }
            return null;
        }
        if (v instanceof List<?> l) {
            for (Object e : l) {
                String r = findRoleMarker(e, depth + 1);
                if (r != null) return r;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------- schemas

    private static final Set<String> KEYWORDS = Set.of("type", "properties", "required", "additionalProperties", "items",
        "enum", "const", "minLength", "maxLength", "pattern", "minimum", "maximum", "minItems", "maxItems");
    private static final Set<String> TYPES = Set.of("object", "array", "string", "number", "integer", "boolean", "null");

    private void checkSchema(Object schema, String path) {
        if (!(schema instanceof Map<?, ?> m)) throw new SchemaError(path + " must be an object");
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            String at = path + "." + k;
            if (!KEYWORDS.contains(k)) throw new SchemaError(at + " is not a supported keyword");
            switch (k) {
                case "type" -> {
                    if (v instanceof String s) {
                        if (!TYPES.contains(s)) throw new SchemaError(at + " names an unknown type");
                    } else if (v instanceof List<?> l && !l.isEmpty()) {
                        for (Object t : l) if (!(t instanceof String ts) || !TYPES.contains(ts)) throw new SchemaError(at + " names an unknown type");
                    } else {
                        throw new SchemaError(at + " must be a type name or a list of them");
                    }
                }
                case "properties" -> {
                    if (!(v instanceof Map<?, ?> props)) throw new SchemaError(at + " must be an object");
                    for (Map.Entry<?, ?> p : props.entrySet()) checkSchema(p.getValue(), at + "." + p.getKey());
                }
                case "required" -> {
                    if (!(v instanceof List<?> l)) throw new SchemaError(at + " must be a list of names");
                    for (Object n : l) if (!(n instanceof String)) throw new SchemaError(at + " must be a list of names");
                }
                case "additionalProperties" -> {
                    if (!(v instanceof Boolean)) throw new SchemaError(at + " must be true or false");
                }
                case "items" -> checkSchema(v, at);
                case "enum" -> {
                    if (!(v instanceof List<?> l) || l.isEmpty()) throw new SchemaError(at + " must be a non-empty list");
                }
                case "const" -> { }
                case "minLength", "maxLength", "minItems", "maxItems" -> {
                    if (!(v instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() != Math.floor(n.doubleValue())) {
                        throw new SchemaError(at + " must be a non-negative integer");
                    }
                }
                case "minimum", "maximum" -> {
                    if (!(v instanceof Number n) || Double.isNaN(n.doubleValue())) throw new SchemaError(at + " must be a number");
                }
                case "pattern" -> {
                    if (!(v instanceof String s)) throw new SchemaError(at + " must be a string");
                    try {
                        patterns.put(s, Pattern.compile(s));
                    } catch (java.util.regex.PatternSyntaxException ex) {
                        throw new SchemaError(at + " is not a valid regular expression");
                    }
                }
                default -> throw new SchemaError(at + " is not a supported keyword");
            }
        }
    }

    private static String typeOf(Object v) {
        if (v == null) return "null";
        if (v instanceof String) return "string";
        if (v instanceof Boolean) return "boolean";
        if (v instanceof Number) return "number";
        if (v instanceof Map) return "object";
        if (v instanceof List) return "array";
        return "other";
    }

    private static boolean isInteger(Number n) {
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte || n instanceof java.math.BigInteger) return true;
        double d = n.doubleValue();
        return !Double.isInfinite(d) && !Double.isNaN(d) && d == Math.floor(d);
    }

    private static boolean matchesType(Object v, String t) {
        return switch (t) {
            case "integer" -> v instanceof Number n && isInteger(n);
            default -> typeOf(v).equals(t);
        };
    }

    /** The first violation as a short message, or null when the value satisfies the schema. */
    @SuppressWarnings("unchecked")
    private String validate(Object v, Map<String, Object> schema, String path) {
        Object type = schema.get("type");
        if (type != null) {
            List<String> types = type instanceof String s ? List.of(s) : (List<String>) type;
            boolean ok = false;
            for (String t : types) if (matchesType(v, t)) { ok = true; break; }
            if (!ok) return path + " must be of type " + String.join(" or ", types);
        }
        if (schema.containsKey("const") && !jsonEquals(v, schema.get("const"))) return path + " does not equal the required value";
        if (schema.get("enum") instanceof List<?> en) {
            boolean ok = false;
            for (Object e : en) if (jsonEquals(v, e)) { ok = true; break; }
            if (!ok) return path + " is not one of the allowed values";
        }
        if (v instanceof String s) {
            int len = s.codePointCount(0, s.length());
            if (schema.get("minLength") instanceof Number n && len < n.longValue()) return path + " is shorter than " + n.longValue();
            if (schema.get("maxLength") instanceof Number n && len > n.longValue()) return path + " is longer than " + n.longValue();
            if (schema.get("pattern") instanceof String p && !patterns.get(p).matcher(s).find()) return path + " does not match the pattern";
        }
        if (v instanceof Number n) {
            BigDecimal bd = toBigDecimal(n);
            if (schema.get("minimum") instanceof Number m && bd.compareTo(toBigDecimal(m)) < 0) return path + " is below the minimum";
            if (schema.get("maximum") instanceof Number m && bd.compareTo(toBigDecimal(m)) > 0) return path + " is above the maximum";
        }
        if (v instanceof List<?> l) {
            if (schema.get("minItems") instanceof Number n && l.size() < n.longValue()) return path + " has fewer than " + n.longValue() + " items";
            if (schema.get("maxItems") instanceof Number n && l.size() > n.longValue()) return path + " has more than " + n.longValue() + " items";
            if (schema.get("items") instanceof Map<?, ?> is) {
                for (int i = 0; i < l.size(); i++) {
                    String err = validate(l.get(i), (Map<String, Object>) is, path + "[" + i + "]");
                    if (err != null) return err;
                }
            }
        }
        if (v instanceof Map<?, ?> m) {
            if (schema.get("required") instanceof List<?> req) {
                for (Object r : req) if (!m.containsKey(r)) return path + "." + r + " is required";
            }
            Map<String, Object> props = schema.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            if (Boolean.FALSE.equals(schema.get("additionalProperties"))) {
                for (Object k : m.keySet()) if (!props.containsKey(k)) return path + "." + k + " is not allowed";
            }
            for (Map.Entry<String, Object> p : props.entrySet()) {
                if (m.containsKey(p.getKey())) {
                    String err = validate(m.get(p.getKey()), (Map<String, Object>) p.getValue(), path + "." + p.getKey());
                    if (err != null) return err;
                }
            }
        }
        return null;
    }

    private static BigDecimal toBigDecimal(Number n) {
        if (n instanceof BigDecimal b) return b;
        if (n instanceof java.math.BigInteger b) return new BigDecimal(b);
        if (n instanceof Integer || n instanceof Long || n instanceof Short || n instanceof Byte) return BigDecimal.valueOf(n.longValue());
        return new BigDecimal(Double.toString(n.doubleValue()));
    }

    private static boolean jsonEquals(Object a, Object b) {
        if (a == null || b == null) return a == b;
        if (a instanceof Number x && b instanceof Number y) return toBigDecimal(x).compareTo(toBigDecimal(y)) == 0;
        if (a instanceof Map<?, ?> x && b instanceof Map<?, ?> y) {
            if (x.size() != y.size()) return false;
            for (Map.Entry<?, ?> e : x.entrySet()) if (!y.containsKey(e.getKey()) || !jsonEquals(e.getValue(), y.get(e.getKey()))) return false;
            return true;
        }
        if (a instanceof List<?> x && b instanceof List<?> y) {
            if (x.size() != y.size()) return false;
            for (int i = 0; i < x.size(); i++) if (!jsonEquals(x.get(i), y.get(i))) return false;
            return true;
        }
        return a.equals(b);
    }

    /** Plain JSON value model (Map, List, String, Number, Boolean, null) for anything Jackson can read. */
    private static Object toJson(Object v) {
        if (v == null || v instanceof String || v instanceof Number || v instanceof Boolean || v instanceof Map || v instanceof List) return v;
        if (v instanceof JsonNode n) {
            try {
                return HttpTransport.JSON.treeToValue(n, Object.class);
            } catch (JsonProcessingException e) {
                throw new Refusal(Reason.INPUT_SCHEMA, "the value is not JSON");
            }
        }
        try {
            return HttpTransport.JSON.convertValue(v, Object.class);
        } catch (IllegalArgumentException e) {
            throw new Refusal(Reason.INPUT_SCHEMA, "the value is not JSON");
        }
    }
}
