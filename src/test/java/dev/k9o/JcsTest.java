package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The platform's JCS code-point profile: the shared vectors plus the edges of each rule. */
class JcsTest {

    @Test
    void everySharedCase() throws Exception {
        JsonNode cases;
        try (InputStream in = JcsTest.class.getResourceAsStream("/fixtures/jcs-codepoint-cases.json")) {
            assertNotNull(in);
            cases = HttpTransport.JSON.readTree(in).get("cases");
        }
        assertEquals(25, cases.size());
        for (JsonNode c : cases) {
            String name = c.get("name").asText();
            Object asObject = HttpTransport.JSON.treeToValue(c.get("input"), Object.class);
            if (c.has("expectCanonical")) {
                String want = c.get("expectCanonical").asText();
                assertEquals(want, Jcs.canonicalize(asObject), name);
                assertEquals(want, Jcs.canonicalize(c.get("input")), name + " (tree)");
            } else {
                assertTrue(c.has("expectError"), name);
                assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(asObject), name);
                assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(c.get("input")), name + " (tree)");
            }
        }
    }

    @Test
    void nanInfinityAndFractionsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(Map.of("x", Float.NaN)));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(0.5));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(new BigDecimal("4.50")));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(List.of(1, 2.5)));
    }

    @Test
    void integersOnlyUpToTwoToThe53Minus1() {
        assertEquals("9007199254740991", Jcs.canonicalize(9007199254740991L));
        assertEquals("-9007199254740991", Jcs.canonicalize(BigInteger.valueOf(-9007199254740991L)));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(9007199254740992L));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(BigInteger.TEN.pow(30)));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(1e30));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize(new BigDecimal("1E30")));
    }

    @Test
    void wholeNumbersOfOtherTypesAreIntegers() {
        assertEquals("1", Jcs.canonicalize(1.0));
        assertEquals("0", Jcs.canonicalize(-0.0));
        assertEquals("0", Jcs.canonicalize(0));
        assertEquals("100", Jcs.canonicalize(new BigDecimal("1E2")));
        assertEquals("-12", Jcs.canonicalize(-12L));
    }

    @Test
    void keysSortByCodePointNotUtf16Unit() {
        Map<String, Object> in = new LinkedHashMap<>();
        in.put("😀", 1); // U+1F600, surrogates D83D DE00
        in.put("ﬀ", 2);       // U+FB00
        in.put("b", 3);
        in.put("a", 4);
        assertEquals("{\"a\":4,\"b\":3,\"ﬀ\":2,\"😀\":1}", Jcs.canonicalize(in));
    }

    @Test
    void stringEscapes() {
        assertEquals("\"\\b\\t\\n\\f\\r\\u0000\\u001f\\\"\\\\/\u007f \"", Jcs.canonicalize("\b\t\n\f\r\u0000\u001f\"\\/\u007f "));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("\ud83d"));
        assertThrows(IllegalArgumentException.class, () -> Jcs.canonicalize("\ude00x"));
    }

    @Test
    void jsonNodeAndNesting() throws Exception {
        var node = HttpTransport.JSON.readTree("{ \"b\": [1, 2, {\"z\": null, \"a\": true}], \"a\": \"x\" }");
        assertEquals("{\"a\":\"x\",\"b\":[1,2,{\"a\":true,\"z\":null}]}", Jcs.canonicalize(node));
        assertEquals("{}", Jcs.canonicalize(Map.of()));
        assertEquals("[]", Jcs.canonicalize(List.of()));
    }
}
