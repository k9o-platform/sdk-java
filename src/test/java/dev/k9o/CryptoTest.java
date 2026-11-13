package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** crypto(): route, body and decoding of encrypt, decrypt and rewrap; client-side validation; every error; no retry. */
class CryptoTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record Call(String method, String path, String body, String query) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"data\":{\"items\":[]},\"meta\":{}}";
    volatile String retryAfter;
    /** Per-path-suffix answers: {status, body}; falls back to status/answer. */
    final java.util.Map<String, java.util.Deque<Object[]>> scripted = new java.util.concurrent.ConcurrentHashMap<>();
    volatile String modeAnswer = "{\"data\":{\"custodyMode\":\"PLATFORM_MANAGED\",\"epoch\":\"e1\"},\"meta\":{}}";

    void script(String suffix, int st, String json) {
        scripted.computeIfAbsent(suffix, k -> new java.util.concurrent.ConcurrentLinkedDeque<>()).add(new Object[] {st, json});
    }

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestURI().getRawQuery()));
            String path = ex.getRequestURI().getRawPath();
            int st = status;
            String ans = answer;
            var q = scripted.get(path.substring(path.lastIndexOf('/') + 1));
            Object[] sc = q == null ? null : q.poll();
            if (sc != null) { st = (Integer) sc[0]; ans = (String) sc[1]; }
            else if (path.endsWith("/crypto/mode")) { st = 200; ans = modeAnswer; }
            byte[] b = ans.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
            ex.sendResponseHeaders(st, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    Crypto crypto() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").build()).crypto();
    }

    static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    /** The call at index i, ignoring mode checks. */
    List<Call> posts() { return calls.stream().filter(c -> c.method().equals("POST")).toList(); }

    JsonNode body(int i) throws Exception { return JSON.readTree(posts().get(i).body()); }

    @Test
    void encryptSendsBase64PlaintextAndDecodesCiphertextsInOrder() throws Exception {
        answer = "{\"data\":{\"items\":[{\"ciphertext\":\"AQID\"},{\"ciphertext\":\"BAUG\",\"extra\":1},{\"code\":\"K9O_X\",\"message\":\"nope\"}]},\"meta\":{}}";
        var out = crypto().encrypt("billing-2026", List.of(Crypto.Plain.of("one"), Crypto.Plain.of("two".getBytes(StandardCharsets.UTF_8)).withContext("order:7"),
            Crypto.Plain.of("three")));
        var c = posts().get(0);
        assertEquals("POST", c.method());
        assertEquals("/@portal/crypto/encrypt", c.path());
        JsonNode b = body(0);
        assertEquals(List.of("crypt", "items", "custodyEpoch"), List.copyOf(iter(b.fieldNames())));
        assertEquals("billing-2026", b.get("crypt").asText());
        assertEquals(b64("one"), b.get("items").get(0).get("plaintext").asText());
        assertFalse(b.get("items").get(0).has("context"));
        assertEquals("order:7", b.get("items").get(1).get("context").asText());
        var r = out.data();
        assertEquals(3, r.size());
        assertEquals("AQID", r.get(0).ciphertext());
        assertTrue(r.get(0).ok());
        assertEquals("BAUG", r.get(1).ciphertext());
        assertEquals(1, r.get(1).raw().get("extra"), "unknown fields are kept");
        assertFalse(r.get(2).ok());
        assertEquals("K9O_X", r.get(2).code());
        assertEquals("nope", r.get(2).message());
    }

    static <T> List<T> iter(java.util.Iterator<T> it) {
        var l = new java.util.ArrayList<T>();
        it.forEachRemaining(l::add);
        return l;
    }

    @Test
    void decryptSendsCiphertextAsGivenAndDecodesPlaintext() throws Exception {
        answer = "{\"data\":{\"items\":[{\"plaintext\":\"" + b64("héllo") + "\"},{\"code\":\"K9O_ENVELOPE_INTEGRITY_FAILURE\",\"message\":\"cannot open\"}]},\"meta\":{}}";
        var out = crypto().decrypt("billing-2026", List.of(Crypto.Sealed.of("AQID").withContext("order:7"), Crypto.Sealed.of("BAUG")));
        assertEquals("/@portal/crypto/decrypt", posts().get(0).path());
        JsonNode b = body(0);
        assertEquals("AQID", b.get("items").get(0).get("ciphertext").asText());
        assertEquals("order:7", b.get("items").get(0).get("context").asText());
        assertFalse(b.get("items").get(1).has("context"));
        assertFalse(b.get("items").get(0).has("plaintext"));
        assertEquals("héllo", out.data().get(0).text());
        assertArrayEquals("héllo".getBytes(StandardCharsets.UTF_8), out.data().get(0).plaintext());
        assertEquals("K9O_ENVELOPE_INTEGRITY_FAILURE", out.data().get(1).code());
        assertNull(out.data().get(1).plaintext());
    }

    @Test
    void rewrapUsesItsRouteAndReturnsCiphertexts() throws Exception {
        answer = "{\"data\":{\"items\":[{\"ciphertext\":\"NEW1\"}]},\"meta\":{}}";
        var out = crypto().rewrap("k", List.of(Crypto.Sealed.of("OLD1")));
        assertEquals("/@portal/crypto/rewrap", posts().get(0).path());
        assertEquals("OLD1", body(0).get("items").get(0).get("ciphertext").asText());
        assertEquals("NEW1", out.data().get(0).ciphertext());
    }

    @Test
    void validatesBeforeSendingAnything() {
        var c = crypto();
        var one = List.of(Crypto.Plain.of("x"));
        for (String bad : new String[] {null, "", "Upper", "has space", "under_score", "a".repeat(65)}) {
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt(bad, one)).getCode(), String.valueOf(bad));
        }
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt("k", List.of())).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt("k", null)).getCode());
        var many = new java.util.ArrayList<Crypto.Plain>();
        for (int i = 0; i < 101; i++) many.add(Crypto.Plain.of("x"));
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt("k", many)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt("k", List.of(new Crypto.Plain(null, null)))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.encrypt("k", List.of(Crypto.Plain.of("x").withContext("c".repeat(257))))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.decrypt("k", List.of(Crypto.Sealed.of("")))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.rewrap("k", List.of(Crypto.Sealed.of("x").withContext("é".repeat(129))))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.decrypt("Bad", List.of(Crypto.Sealed.of("x")))).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void cryptNamedActionsIsRefusedWithZeroRequests() {
        var c = crypto();
        for (var op : List.<java.util.function.Supplier<Object>>of(
                () -> c.encrypt("actions", List.of(Crypto.Plain.of("x"))),
                () -> c.decrypt("actions", List.of(Crypto.Sealed.of("x"))),
                () -> c.rewrap("actions", List.of(Crypto.Sealed.of("x"))),
                () -> c.federatedToken("actions", "encrypt"))) {
            var e = assertThrows(KError.class, op::get);
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
            assertEquals("crypt must be 1 to 64 characters of a-z, 0-9 and -, and not \"actions\"", e.getMessage());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void cryptNamedActionsDashTwoIsAccepted() throws Exception {
        answer = "{\"data\":{\"items\":[{\"ciphertext\":\"x\"}]},\"meta\":{}}";
        crypto().encrypt("actions-2", List.of(Crypto.Plain.of("x")));
        assertEquals("actions-2", body(0).get("crypt").asText());
    }

    @Test
    void encryptCryptNotFoundAndRetiredPassThroughUnchanged() {
        status = 404;
        answer = "{\"code\":\"K9O_CRYPT_NOT_FOUND\",\"message\":\"m\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> crypto().encrypt("billing", List.of(Crypto.Plain.of("x"))));
        assertEquals(Crypto.CRYPT_NOT_FOUND, e.getCode());
        assertEquals("K9O_CRYPT_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
    }

    @Test
    void encryptCryptRetiredPassesThroughUnchanged() {
        status = 409;
        answer = "{\"code\":\"K9O_CRYPT_RETIRED\",\"message\":\"m\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> crypto().encrypt("billing", List.of(Crypto.Plain.of("x"))));
        assertEquals(Crypto.CRYPT_RETIRED, e.getCode());
        assertEquals("K9O_CRYPT_RETIRED", e.getCode());
        assertEquals(409, e.getStatus());
    }

    @Test
    void boundariesAreAccepted() {
        answer = "{\"data\":{\"items\":[{\"ciphertext\":\"x\"}]},\"meta\":{}}";
        crypto().encrypt("a".repeat(64), List.of(new Crypto.Plain(new byte[64 * 1024], "c".repeat(256))));
        assertEquals(2, calls.size());
    }

    @Test
    void sizeLimitsAreEnforcedClientSideWithTheServersCode() {
        var c = crypto();
        var e = assertThrows(KError.class, () -> c.encrypt("k", List.of(new Crypto.Plain(new byte[64 * 1024 + 1], null))));
        assertEquals("K9O_PAYLOAD_TOO_LARGE", e.getCode());
        var items = new java.util.ArrayList<Crypto.Plain>();
        for (int i = 0; i < 17; i++) items.add(new Crypto.Plain(new byte[64 * 1024], null)); // 17 * 64 KiB > 1 MiB
        assertEquals("K9O_PAYLOAD_TOO_LARGE", assertThrows(KError.class, () -> c.encrypt("k", items)).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void missingProjectContext() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build()).crypto();
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.encrypt("k", List.of(Crypto.Plain.of("x")))).getCode());
    }

    KError fail(int st, String code, String context) {
        status = st;
        answer = "{\"code\":\"" + code + "\",\"message\":\"m\",\"context\":" + context + "}";
        var e = assertThrows(KError.class, () -> crypto().decrypt("k", List.of(Crypto.Sealed.of("x"))));
        assertEquals(code, e.getCode());
        assertEquals(st, e.getStatus());
        return e;
    }

    @Test
    void everyErrorIsTyped() {
        assertTrue(fail(400, "K9O_ENVELOPE_INTEGRITY_FAILURE", "{}").getContext().isEmpty());
        assertEquals("2026-11-20T00:00:00.000Z", fail(409, "K9O_SEALED_UNDER_RETIRED_KEY", "{\"resealableUntil\":\"2026-11-20T00:00:00.000Z\"}").getContext().get("resealableUntil"));
        fail(413, "K9O_PAYLOAD_TOO_LARGE", "{}");
        fail(503, "K9O_KEY_SERVICE_UNAVAILABLE", "{}");
        fail(503, "K9O_BYOK_KEY_UNREACHABLE", "{}");
        fail(503, "K9O_HYOK_CLUSTER_UNREACHABLE", "{}");
        fail(404, "K9O_NOT_FOUND", "{}");
    }

    @Test
    void rateLimitGivesRetryAfter() {
        retryAfter = "7";
        assertEquals(7.0, fail(429, "K9O_RATE_LIMITED", "{}").retryAfterSec());
    }

    @Test
    void aFiveHundredAndThreeIsNeverRetried() {
        fail(503, "K9O_KEY_SERVICE_UNAVAILABLE", "{}");
        assertEquals(1, calls.size());
        calls.clear();
        status = 503;
        modeAnswer = "{\"data\":{\"custodyMode\":\"PLATFORM_MANAGED\",\"epoch\":\"e1\"},\"meta\":{}}";
        script("mode", 200, modeAnswer);
        script("mode", 200, modeAnswer);
        assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("x"))));
        assertThrows(KError.class, () -> crypto().rewrap("k", List.of(Crypto.Sealed.of("x"))));
        assertEquals(2, posts().size());
    }

    @Test
    void aMalformedAnswerIsAnInternalError() {
        answer = "{\"data\":{\"nope\":1},\"meta\":{}}";
        assertEquals("K9O_INTERNAL_ERROR", assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("x")))).getCode());
    }

    // ---- custody router ----

    static final String MODE_PLATFORM = "{\"data\":{\"custodyMode\":\"PLATFORM_MANAGED\",\"epoch\":\"e1\"},\"meta\":{}}";
    static final String ITEM = "{\"data\":{\"items\":[{\"ciphertext\":\"CT\"}]},\"meta\":{}}";
    static final String FLOW_409 = "{\"code\":\"K9O_ZK_STORE_FLOW_REQUIRED\",\"message\":\"m\",\"context\":{\"custodyMode\":\"BYOK\",\"route\":\"client\",\"epoch\":\"e2\"}}";

    static String mode(String m, String epoch) {
        return "{\"data\":{\"custodyMode\":\"" + m + "\",\"epoch\":\"" + epoch + "\"},\"meta\":{}}";
    }

    static String modeRoute(String m, String epoch, String routeJson) {
        return "{\"data\":{\"custodyMode\":\"" + m + "\"" + (epoch == null ? "" : ",\"epoch\":\"" + epoch + "\"") + ",\"route\":" + routeJson + "},\"meta\":{}}";
    }

    static class FakeCore implements Crypto.ClientCore {
        boolean enforces = false;
        @Override public boolean enforcesExpectedKeyRefs() { return enforces; }
        final List<String> ops = new java.util.ArrayList<>();
        final List<Crypto.CoreOptions> seen = new java.util.ArrayList<>();
        public List<Crypto.Result> encrypt(String k, List<Crypto.Plain> items, Crypto.CoreOptions o) { seen.add(o); ops.add("encrypt"); return List.of(new Crypto.Result("CORE", null, null, null, java.util.Map.of())); }
        public List<Crypto.Result> decrypt(String k, List<Crypto.Sealed> items, Crypto.CoreOptions o) { seen.add(o); ops.add("decrypt"); return List.of(new Crypto.Result(null, "p".getBytes(), null, null, java.util.Map.of())); }
        public List<Crypto.Result> rewrap(String k, List<Crypto.Sealed> items, Crypto.CoreOptions o) { seen.add(o); ops.add("rewrap"); return List.of(new Crypto.Result("RW", null, null, null, java.util.Map.of())); }
    }

    long count(String suffix) { return calls.stream().filter(c -> c.path().endsWith(suffix)).count(); }

    @Test
    void platformManagedChecksModeOnceThenPostsWithTheEpoch() throws Exception {
        answer = ITEM;
        crypto().encrypt("k", List.of(Crypto.Plain.of("a"), Crypto.Plain.of("b")));
        assertEquals(2, calls.size());
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/crypto/mode", calls.get(0).path());
        assertEquals("crypt=k", calls.get(0).query());
        assertEquals("POST", calls.get(1).method());
        assertEquals("e1", body(0).get("custodyEpoch").asText());
    }

    @Test
    void encryptModeCheckNamesTheCryptAndPublicModeDoesNot() throws Exception {
        answer = ITEM;
        crypto().encrypt("customer-notes", List.of(Crypto.Plain.of("a")));
        assertEquals("/@portal/crypto/mode", calls.get(0).path());
        assertEquals("crypt=customer-notes", calls.get(0).query());
        calls.clear();
        crypto().mode();
        assertEquals(1, calls.size());
        assertEquals("/@portal/crypto/mode", calls.get(0).path());
        assertNull(calls.get(0).query());
    }

    @Test
    void publicModeTakesAnOptionalCrypt() {
        var m = crypto().mode("customer-notes").data();
        assertEquals("PLATFORM_MANAGED", m.custodyMode());
        assertEquals(1, calls.size());
        assertEquals("/@portal/crypto/mode", calls.get(0).path());
        assertEquals("crypt=customer-notes", calls.get(0).query());
        calls.clear();
        crypto().mode();
        assertNull(calls.get(0).query());
        calls.clear();
        for (String bad : new String[] {null, "", "actions", "Upper", "a b", "x".repeat(65)}) {
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> crypto().mode(bad)).getCode(), String.valueOf(bad));
        }
        assertEquals(0, calls.size());
    }

    @Test
    void modeReturnsTheRouteTheGatewaySent() {
        for (String route : new String[] {"server", "client"}) {
            modeAnswer = modeRoute("HYOK", "e", "\"" + route + "\"");
            assertEquals(route, crypto().mode().data().route());
            assertEquals(route, crypto().mode("customer-notes").data().route());
        }
    }

    @Test
    void modeRouteIsNullWhenAbsentOrUnknown() {
        for (String answerJson : new String[] {mode("HYOK", "e"), modeRoute("HYOK", "e", "\"edge\""), modeRoute("HYOK", "e", "null"), modeRoute("HYOK", "e", "7")}) {
            modeAnswer = answerJson;
            assertNull(crypto().mode().data().route(), answerJson);
            assertNull(crypto().mode("customer-notes").data().route(), answerJson);
            assertEquals("HYOK", crypto().mode().data().custodyMode());
        }
        assertNull(new Crypto.Mode("BYOK", "e").route());
    }

    @Test
    void serverModesRouteToTheServer() {
        for (String m : new String[] {"PLATFORM_MANAGED", "CONFIDENTIAL_WORKLOAD", "SKR"}) {
            calls.clear();
            modeAnswer = mode(m, "e");
            answer = ITEM;
            crypto().encrypt("k", List.of(Crypto.Plain.of("a")));
            assertEquals(1, count("/crypto/encrypt"), m);
        }
    }

    @Test
    void modeReadsTyped() {
        modeAnswer = mode("BYOK", "ep-9");
        var m = crypto().mode().data();
        assertEquals("BYOK", m.custodyMode());
        assertEquals("ep-9", m.epoch());
    }

    @Test
    void twoEncryptsMakeTwoModeChecks() {
        answer = ITEM;
        var c = crypto();
        c.encrypt("k", List.of(Crypto.Plain.of("a")));
        c.encrypt("k", List.of(Crypto.Plain.of("a")));
        assertEquals(2, count("/crypto/mode"));
        assertEquals(2, count("/crypto/encrypt"));
    }

    @Test
    void byokWithNoRouteUsesTheClientCore() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        assertEquals("CORE", c.encrypt("k", List.of(Crypto.Plain.of("secret"))).data().get(0).ciphertext());
        assertEquals(List.of("encrypt"), core.ops);
        assertEquals(0, count("/crypto/encrypt"));
    }

    @Test
    void byokAndHyokWithRouteClientUseTheCore() {
        for (String m : new String[] {"BYOK", "HYOK"}) {
            calls.clear();
            modeAnswer = modeRoute(m, "e", "\"client\"");
            var core = new FakeCore();
            var c = crypto();
            c.useClientCore(core);
            c.encrypt("k", List.of(Crypto.Plain.of("secret")));
            assertEquals(List.of("encrypt"), core.ops, m);
            assertEquals(0, count("/crypto/encrypt"), m);
        }
    }

    @Test
    void byokWithRouteServerSendsToTheServerAndNeverCallsTheCore() throws Exception {
        for (String m : new String[] {"BYOK", "HYOK"}) {
            calls.clear();
            modeAnswer = modeRoute(m, "ep7", "\"server\"");
            answer = ITEM;
            var core = new FakeCore();
            var c = crypto();
            c.useClientCore(core);
            assertEquals("CT", c.encrypt("k", List.of(Crypto.Plain.of("secret"))).data().get(0).ciphertext());
            assertEquals(1, count("/crypto/encrypt"), m);
            assertEquals("ep7", body(0).get("custodyEpoch").asText());
            assertTrue(core.ops.isEmpty(), m);
        }
    }

    private void assertUnknownRouteRefused(String route) {
        for (String m : new String[] {"BYOK", "PLATFORM_MANAGED"}) {
            calls.clear();
            modeAnswer = modeRoute(m, "e", route);
            var core = new FakeCore();
            var c = crypto();
            c.useClientCore(core);
            var e = assertThrows(KError.class, () -> c.encrypt("k", List.of(Crypto.Plain.of("secret"))), m + route);
            assertEquals("K9O_CRYPTO_CUSTODY_UNKNOWN", e.getCode());
            assertNull(e.getStatus());
            assertEquals(m, e.getContext().get("custodyMode"));
            if (route.startsWith("\"") && !route.equals("null")) assertEquals(route.substring(1, route.length() - 1), e.getContext().get("route"));
            else assertFalse(e.getContext().containsKey("route"), route);
            assertEquals(0, count("/crypto/encrypt"), m + route);
            assertTrue(core.ops.isEmpty(), m + route);
        }
    }

    @Test void anUnknownStringRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("\"bogus\""); }
    @Test void aNumericRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("5"); }
    @Test void anotherNumericRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("123"); }
    @Test void aNullRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("null"); }
    @Test void aWrongCaseRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("\"Server\""); }
    @Test void anObjectRouteIsRefusedAndNothingIsSent() { assertUnknownRouteRefused("{}"); }

    @Test
    void byokRouteServerWithoutAnEpochIsRefused() {
        for (String ep : new String[] {null, ""}) {
            calls.clear();
            modeAnswer = modeRoute("BYOK", ep, "\"server\"");
            var core = new FakeCore();
            var c = crypto();
            c.useClientCore(core);
            var e = assertThrows(KError.class, () -> c.encrypt("k", List.of(Crypto.Plain.of("secret"))));
            assertEquals("K9O_CRYPTO_CUSTODY_UNKNOWN", e.getCode());
            assertEquals("BYOK", e.getContext().get("custodyMode"));
            assertEquals(0, count("/crypto/encrypt"));
            assertTrue(core.ops.isEmpty());
        }
    }

    @Test
    void platformManagedIsUnchangedByAKnownRoute() {
        for (String route : new String[] {null, "\"server\"", "\"client\""}) {
            calls.clear();
            modeAnswer = route == null ? mode("PLATFORM_MANAGED", "e") : modeRoute("PLATFORM_MANAGED", "e", route);
            answer = ITEM;
            var core = new FakeCore();
            var c = crypto();
            c.useClientCore(core);
            c.encrypt("k", List.of(Crypto.Plain.of("a")));
            assertEquals(1, count("/crypto/encrypt"), String.valueOf(route));
            assertTrue(core.ops.isEmpty());
        }
    }

    @Test
    void theRouteIsReadFreshOnEveryEncrypt() {
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        answer = ITEM;
        modeAnswer = modeRoute("BYOK", "e", "\"server\"");
        c.encrypt("k", List.of(Crypto.Plain.of("a")));
        modeAnswer = mode("BYOK", "e");
        c.encrypt("k", List.of(Crypto.Plain.of("a")));
        assertEquals(2, count("/crypto/mode"));
        assertEquals(1, count("/crypto/encrypt"));
        assertEquals(List.of("encrypt"), core.ops);
    }

    @Test
    void byokAndHyokWithoutACoreRaiseAndSendNoPlaintext() {
        for (String m : new String[] {"BYOK", "HYOK"}) {
            calls.clear();
            modeAnswer = mode(m, "e");
            var e = assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("secret"))));
            assertEquals("K9O_ZK_CLIENT_CORE_UNAVAILABLE", e.getCode());
            assertEquals(501, e.getStatus());
            assertEquals(m, e.getContext().get("custodyMode"));
            assertEquals("kibo-client-core", e.getContext().get("requirement"));
            assertEquals(0, count("/crypto/encrypt"));
        }
    }

    Crypto cryptoWith(List<String> refs) {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").expectedKeyRefs(refs).build()).crypto();
    }

    @Test
    void expectedKeyRefsArePassedToTheCoreOnEveryCall() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        core.enforces = true;
        var c = cryptoWith(List.of("arn:aws:kms:eu-west-1:111122223333:key/abc"));
        c.useClientCore(core);
        c.encrypt("k", List.of(Crypto.Plain.of("secret")));
        script("decrypt", 409, FLOW_409);
        c.decrypt("k", List.of(Crypto.Sealed.of("CT")));
        script("rewrap", 409, FLOW_409);
        c.rewrap("k", List.of(Crypto.Sealed.of("CT")));
        assertEquals(3, core.seen.size());
        for (var o : core.seen) assertEquals(List.of("arn:aws:kms:eu-west-1:111122223333:key/abc"), o.expectedKeyRefs());
        assertThrows(UnsupportedOperationException.class, () -> core.seen.get(0).expectedKeyRefs().add("x"));
    }

    @Test
    void expectedKeyRefsAreEmptyWhenNotSet() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        c.encrypt("k", List.of(Crypto.Plain.of("secret")));
        assertTrue(core.seen.get(0).expectedKeyRefs().isEmpty());
    }

    @Test
    void invalidExpectedKeyRefsAreRefusedAtBuild() {
        var bad = List.of(List.<String>of(), List.of(""), List.of("  "), List.of("a b"), List.of("arn:aws:kms:*"), java.util.Arrays.asList("ok", null));
        for (var refs : bad) {
            var e = assertThrows(KError.class, () -> cryptoWith(refs), String.valueOf(refs));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
            assertEquals("expectedKeyRefs", e.getContext().get("field"));
            assertTrue(e.getMessage().startsWith("expectedKeyRefs: "));
        }
    }

    @Test
    void anUndeclaredCoreWithTheListSetIsRefusedOnEveryOperationAndNeverCalled() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        var c = cryptoWith(List.of("arn:aws:kms:eu-west-1:111122223333:key/abc"));
        c.useClientCore(core);
        script("decrypt", 409, FLOW_409);
        script("rewrap", 409, FLOW_409);
        List<Runnable> ops = List.of(
            () -> c.encrypt("k", List.of(Crypto.Plain.of("secret"))),
            () -> c.decrypt("k", List.of(Crypto.Sealed.of("CT"))),
            () -> c.rewrap("k", List.of(Crypto.Sealed.of("CT"))));
        for (Runnable op : ops) {
            var e = assertThrows(KError.class, op::run);
            assertEquals(Crypto.KEY_PIN_UNENFORCED, e.getCode());
            assertEquals("K9O_CRYPTO_KEY_PIN_UNENFORCED", e.getCode());
            assertNull(e.getStatus());
            assertEquals("BYOK", e.getContext().get("custodyMode"));
            assertEquals("client-core-enforces-expected-key-refs", e.getContext().get("requirement"));
            assertTrue(e.getRecommendation().startsWith("Use a client core that enforces the pin"));
        }
        assertTrue(core.ops.isEmpty());
    }

    @Test
    void anUndeclaredCoreWithoutAListWorks() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        assertEquals("CORE", c.encrypt("k", List.of(Crypto.Plain.of("secret"))).data().get(0).ciphertext());
        assertEquals(List.of("encrypt"), core.ops);
    }

    @Test
    void keyRefMatchesFollowsTheProviderRules() {
        Crypto.KeyRefProvider AZ = Crypto.KeyRefProvider.AZURE, V = Crypto.KeyRefProvider.VAULT,
            G = Crypto.KeyRefProvider.GCP, A = Crypto.KeyRefProvider.AWS;
        // Azure: first five parts, lower case, version ignored
        String az = "https://Vault.Example.net/keys/MyKey";
        assertTrue(Crypto.keyRefMatches(az + "/0123456789abcdef0123456789ABCDEF", List.of("https://vault.example.net/keys/mykey"), AZ));
        assertFalse(Crypto.keyRefMatches(az, List.of("https://vault.example.net/keys/other"), AZ));
        assertTrue(Crypto.keyRefMatches("http://H/keys/K/v1", List.of("http://h/keys/k"), AZ));
        // Vault: <path>/<key>, ':version' cut
        assertTrue(Crypto.keyRefMatches("transit/keys/app:3", List.of("transit/keys/app"), V));
        assertFalse(Crypto.keyRefMatches("transit/keys/app:3", List.of("transit/keys/other:3"), V));
        assertTrue(Crypto.keyRefMatches("app:2", List.of("app"), V));
        assertTrue(Crypto.keyRefMatches("k:1", List.of("k"), V));
        // GCP
        String gcp = "projects/p/locations/l/keyRings/r/cryptoKeys/k";
        assertTrue(Crypto.keyRefMatches(gcp + "/cryptoKeyVersions/7", List.of(gcp), G));
        assertTrue(Crypto.keyRefMatches(gcp, List.of(gcp + "/cryptoKeyVersions/2"), G));
        assertFalse(Crypto.keyRefMatches(gcp + "x/cryptoKeyVersions/7", List.of(gcp), G));
        assertTrue(Crypto.keyRefMatches("a/b", List.of("a/b"), G));
        assertFalse(Crypto.keyRefMatches("a/b", List.of("a/c"), G));
        // AWS: whole reference
        String aws = "arn:aws:kms:eu-west-1:111122223333:key/abc";
        assertTrue(Crypto.keyRefMatches(aws, List.of("alias/x", aws), A));
        assertFalse(Crypto.keyRefMatches(aws, List.of(aws + "/extra"), A));
        assertFalse(Crypto.keyRefMatches(aws, List.of(), A));
        assertFalse(Crypto.keyRefMatches("arn:aws:kms:r:1:key/abc", List.of("arn:aws:kms:r:1:key/abd"), A));
        assertFalse(Crypto.keyRefMatches("k:1", List.of("k"), A));
        // the provider is required
        assertThrows(NullPointerException.class, () -> Crypto.keyRefMatches(aws, List.of(aws), null));
    }

    @Test
    void aUrlLookingVaultReferenceComparesByTheVaultRuleOnly() {
        String ref = "https://vault.example.com:8200/transit/keys/orders:3";
        List<String> expected = List.of("https://vault.example.com:8200/transit/keys/orders");
        assertTrue(Crypto.keyRefMatches(ref, expected, Crypto.KeyRefProvider.VAULT));
        // a different key under the same path: the Vault rule tells them apart, the Azure rule (first five parts) would not
        List<String> other = List.of("https://vault.example.com:8200/transit/keys/billing");
        assertFalse(Crypto.keyRefMatches(ref, other, Crypto.KeyRefProvider.VAULT));
        assertTrue(Crypto.keyRefMatches(ref, other, Crypto.KeyRefProvider.AZURE));
        assertFalse(Crypto.keyRefMatches(ref, expected, Crypto.KeyRefProvider.AWS));
        assertFalse(Crypto.keyRefMatches(ref, expected, Crypto.KeyRefProvider.GCP));
    }

    @Test
    void byokWithARegisteredCoreUsesItAndSendsNothingToTheServer() {
        modeAnswer = mode("BYOK", "e");
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        var out = c.encrypt("k", List.of(Crypto.Plain.of("secret")));
        assertEquals("CORE", out.data().get(0).ciphertext());
        assertEquals(List.of("encrypt"), core.ops);
        assertEquals(0, count("/crypto/encrypt"));
    }

    @Test
    void epochRaceReroutesOnceToTheClientCore() {
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        script("mode", 200, MODE_PLATFORM);
        script("mode", 200, mode("BYOK", "e2"));
        script("encrypt", 409, FLOW_409);
        var out = c.encrypt("k", List.of(Crypto.Plain.of("a")));
        assertEquals("CORE", out.data().get(0).ciphertext());
        assertEquals(2, count("/crypto/mode"));
        assertEquals(1, count("/crypto/encrypt"));
    }

    @Test
    void aSecondFlowRequired409IsRaised() {
        script("mode", 200, MODE_PLATFORM);
        script("mode", 200, mode("PLATFORM_MANAGED", "e2"));
        script("encrypt", 409, FLOW_409);
        script("encrypt", 409, FLOW_409);
        var e = assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("a"))));
        assertEquals("K9O_ZK_STORE_FLOW_REQUIRED", e.getCode());
        assertEquals(2, count("/crypto/mode"));
        assertEquals(2, count("/crypto/encrypt"));
    }

    @Test
    void unknownOrMissingModeFailsClosed() {
        for (String m : new String[] {mode("SOMETHING_NEW", "e"), "{\"data\":{\"epoch\":\"e\"},\"meta\":{}}", "{\"data\":{},\"meta\":{}}",
            "{\"data\":{\"custodyMode\":\"PLATFORM_MANAGED\"},\"meta\":{}}", mode("SKR", "")}) {
            calls.clear();
            modeAnswer = m;
            var e = assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("a"))));
            assertEquals(Crypto.CUSTODY_UNKNOWN, e.getCode());
            assertEquals("K9O_CRYPTO_CUSTODY_UNKNOWN", e.getCode());
            assertNull(e.getStatus());
            assertEquals(0, count("/crypto/encrypt"));
        }
    }

    @Test
    void aFailingModeCallFailsClosed() {
        for (int st : new int[] {404, 500}) {
            calls.clear();
            script("mode", st, "{\"code\":\"K9O_NOT_FOUND\",\"message\":\"m\",\"context\":{}}");
            var e = assertThrows(KError.class, () -> crypto().encrypt("k", List.of(Crypto.Plain.of("a"))));
            assertEquals(st, e.getStatus());
            assertEquals("K9O_NOT_FOUND", e.getCode());
            assertEquals(0, count("/crypto/encrypt"));
        }
    }

    @Test
    void decryptAndRewrapRerouteOnAClientRoute409() {
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        script("decrypt", 409, FLOW_409);
        assertArrayEquals("p".getBytes(), c.decrypt("k", List.of(Crypto.Sealed.of("x"))).data().get(0).plaintext());
        script("rewrap", 409, FLOW_409);
        assertEquals("RW", c.rewrap("k", List.of(Crypto.Sealed.of("x"))).data().get(0).ciphertext());
        assertEquals(List.of("decrypt", "rewrap"), core.ops);
        assertEquals(0, count("/crypto/mode"));
    }

    @Test
    void decryptThatSucceedsNeverTouchesTheCore() {
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        answer = "{\"data\":{\"items\":[{\"plaintext\":\"" + b64("ok") + "\"}]},\"meta\":{}}";
        assertEquals("ok", c.decrypt("k", List.of(Crypto.Sealed.of("x"))).data().get(0).text());
        assertTrue(core.ops.isEmpty());
    }

    @Test
    void otherErrorsOnDecryptAndRewrapAreRaisedUnchanged() {
        var core = new FakeCore();
        var c = crypto();
        c.useClientCore(core);
        script("decrypt", 409, "{\"code\":\"K9O_ZK_STORE_FLOW_REQUIRED\",\"message\":\"m\",\"context\":{\"route\":\"server\"}}");
        assertEquals("K9O_ZK_STORE_FLOW_REQUIRED", assertThrows(KError.class, () -> c.decrypt("k", List.of(Crypto.Sealed.of("x")))).getCode());
        script("rewrap", 409, "{\"code\":\"K9O_SEALED_UNDER_RETIRED_KEY\",\"message\":\"m\",\"context\":{\"route\":\"client\"}}");
        assertEquals("K9O_SEALED_UNDER_RETIRED_KEY", assertThrows(KError.class, () -> c.rewrap("k", List.of(Crypto.Sealed.of("x")))).getCode());
        script("decrypt", 400, "{\"code\":\"K9O_ENVELOPE_INTEGRITY_FAILURE\",\"message\":\"m\",\"context\":{}}");
        assertEquals("K9O_ENVELOPE_INTEGRITY_FAILURE", assertThrows(KError.class, () -> c.decrypt("k", List.of(Crypto.Sealed.of("x")))).getCode());
        assertTrue(core.ops.isEmpty());
    }

    @Test
    void clientRoute409WithoutACoreRaisesTheUnavailableError() {
        script("decrypt", 409, FLOW_409);
        var e = assertThrows(KError.class, () -> crypto().decrypt("k", List.of(Crypto.Sealed.of("x"))));
        assertEquals("K9O_ZK_CLIENT_CORE_UNAVAILABLE", e.getCode());
        assertEquals(501, e.getStatus());
        assertEquals("kibo-client-core", e.getContext().get("requirement"));
    }

    // ---- federated token ----

    static final String FED_OK = "{\"data\":{\"token\":\"eyJhbGciOiJSUzI1NiJ9.sig-secret\",\"expiresAt\":\"2026-11-02T10:15:00Z\",\"issuer\":\"https://gw.example\","
        + "\"subject\":\"svc_1\",\"audience\":\"crypto\",\"kid\":\"k1\"},\"meta\":{}}";

    @Test
    void federatedTokenPostsCryptAndOpAndDecodes() throws Exception {
        answer = FED_OK;
        var env = crypto().federatedToken("billing", "decrypt");
        assertEquals(1, calls.size());
        var c = calls.get(0);
        assertEquals("POST", c.method());
        assertEquals("/@portal/crypto/federated-token", c.path());
        assertEquals(JSON.readTree("{\"crypt\":\"billing\",\"op\":\"decrypt\"}"), JSON.readTree(c.body()));
        var t = env.data();
        assertEquals("eyJhbGciOiJSUzI1NiJ9.sig-secret", t.token());
        assertEquals("2026-11-02T10:15:00Z", t.expiresAt());
        assertEquals("https://gw.example", t.issuer());
        assertEquals("svc_1", t.subject());
        assertEquals("crypto", t.audience());
        assertEquals("k1", t.kid());
        assertNull(t.provider());
        assertNull(t.projectId());
    }

    @Test
    void federatedTokenDecodesProviderAndProjectIdWhenPresent() {
        answer = FED_OK.replace("\"kid\":\"k1\"", "\"kid\":\"k1\",\"provider\":\"aws\",\"projectId\":\"proj_1\"");
        var t = crypto().federatedToken("billing", "encrypt").data();
        assertEquals("aws", t.provider());
        assertEquals("proj_1", t.projectId());
        assertTrue(t.toString().contains("provider=aws"));
        assertFalse(t.toString().contains("sig-secret"));
    }

    @Test
    void federatedTokenRefusesBadCryptOrOpWithoutSending() {
        for (String k : new String[] {null, "", "Bad", "a_b", "x".repeat(65)}) {
            var e = assertThrows(KError.class, () -> crypto().federatedToken(k, "encrypt"));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        for (String op : new String[] {null, "", "Encrypt", "sign"}) {
            var e = assertThrows(KError.class, () -> crypto().federatedToken("billing", op));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        assertEquals(0, calls.size());
    }

    @Test
    void federatedToken409And503PassThroughWithoutRetry() {
        status = 409;
        answer = "{\"code\":\"K9O_CUSTODY_SERVER_ROUTE\",\"message\":\"m\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> crypto().federatedToken("billing", "rewrap"));
        assertEquals("K9O_CUSTODY_SERVER_ROUTE", e.getCode());
        assertEquals(409, e.getStatus());
        status = 503;
        answer = "{\"code\":\"K9O_KEY_SERVICE_UNAVAILABLE\",\"message\":\"m\",\"context\":{}}";
        var e2 = assertThrows(KError.class, () -> crypto().federatedToken("billing", "rewrap"));
        assertEquals("K9O_KEY_SERVICE_UNAVAILABLE", e2.getCode());
        assertEquals(503, e2.getStatus());
        assertEquals(2, calls.size());
    }

    @Test
    void federatedTokenWithAMissingFieldIsAnInternalError() {
        for (String f : new String[] {"token", "expiresAt", "issuer", "subject", "audience", "kid"}) {
            calls.clear();
            answer = FED_OK.replace("\"" + f + "\":", "\"x" + f + "\":");
            var e = assertThrows(KError.class, () -> crypto().federatedToken("billing", "encrypt"), f);
            assertEquals("K9O_INTERNAL_ERROR", e.getCode(), f);
            assertFalse(String.valueOf(e.getMessage()).contains("sig-secret"));
        }
        answer = "{\"data\":null,\"meta\":{}}";
        assertEquals("K9O_INTERNAL_ERROR", assertThrows(KError.class, () -> crypto().federatedToken("billing", "encrypt")).getCode());
    }

    @Test
    void federatedTokenIsRedactedInToString() {
        answer = FED_OK;
        var t = crypto().federatedToken("billing", "encrypt").data();
        assertFalse(t.toString().contains("sig-secret"));
        assertFalse(t.toString().contains("eyJ"));
        assertTrue(t.toString().contains("kid=k1"));
    }

    static final String CRYPT_JSON = "{\"name\":\"payments\",\"status\":\"active\",\"createdAt\":\"2026-11-02T10:00:00Z\",\"retiredAt\":null}";

    @Test
    void listCryptsGetsAndDecodes() {
        answer = "{\"data\":{\"crypts\":[" + CRYPT_JSON + ",{\"name\":\"old\",\"status\":\"retired\",\"createdAt\":\"a\",\"retiredAt\":\"b\"}]},\"meta\":{}}";
        var r = crypto().listCrypts().data();
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/crypto/crypts", calls.get(0).path());
        assertEquals(new Crypto.Crypt("payments", "active", "2026-11-02T10:00:00Z", null), r.get(0));
        assertEquals("b", r.get(1).retiredAt());
    }

    @Test
    void listCryptsEmpty() {
        answer = "{\"data\":{\"crypts\":[]},\"meta\":{}}";
        assertTrue(crypto().listCrypts().data().isEmpty());
    }

    @Test
    void createCryptSendsOnlyName() throws Exception {
        status = 201;
        answer = "{\"data\":" + CRYPT_JSON + ",\"meta\":{}}";
        var r = crypto().createCrypt("payments").data();
        assertEquals("payments", r.name());
        assertEquals("POST", calls.get(0).method());
        assertEquals("/@portal/crypto/crypts", calls.get(0).path());
        assertEquals(JSON.readTree("{\"name\":\"payments\"}"), JSON.readTree(calls.get(0).body()));
    }

    @Test
    void retireCryptPostsNoBodyToRetirePath() {
        answer = "{\"data\":{\"name\":\"payments\",\"status\":\"retired\",\"createdAt\":\"a\",\"retiredAt\":\"b\"},\"meta\":{}}";
        var r = crypto().retireCrypt("payments").data();
        assertEquals("retired", r.status());
        assertEquals("POST", calls.get(0).method());
        assertEquals("/@portal/crypto/crypts/payments/retire", calls.get(0).path());
        assertEquals("", calls.get(0).body());
    }

    @Test
    void cryptNameBadRefusedWithNothingSent() {
        for (String bad : new String[] {null, "", "Payments", "a/b", "actions", "x".repeat(65), "../x"}) {
            var e = assertThrows(KError.class, () -> crypto().createCrypt(bad));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
            assertEquals("name", e.getContext().get("field"));
            assertTrue(e.getMessage().startsWith("crypto.crypts.create: name must be 1 to 64"));
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> crypto().retireCrypt(bad)).getCode());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void cryptGatewayRefusalsPassThrough() {
        status = 409;
        answer = "{\"code\":\"K9O_CRYPT_ALREADY_EXISTS\",\"message\":\"m\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> crypto().createCrypt("payments"));
        assertEquals(Crypto.CRYPT_ALREADY_EXISTS, e.getCode());
        assertEquals(409, e.getStatus());
        status = 404;
        answer = "{\"code\":\"K9O_CRYPT_NOT_FOUND\",\"message\":\"m\",\"context\":{}}";
        assertEquals(Crypto.CRYPT_NOT_FOUND, assertThrows(KError.class, () -> crypto().retireCrypt("nope")).getCode());
        status = 403;
        answer = "{\"code\":\"K9O_RBAC_DENY\",\"message\":\"m\",\"context\":{}}";
        assertEquals("K9O_RBAC_DENY", assertThrows(KError.class, () -> crypto().retireCrypt("payments")).getCode());
    }

    @Test
    void cryptMalformedAnswersFailClosed() {
        String[] listBad = {"{\"data\":{},\"meta\":{}}", "{\"data\":{\"crypts\":{}},\"meta\":{}}",
            "{\"data\":{\"crypts\":[{\"status\":\"active\",\"createdAt\":\"a\",\"retiredAt\":null}]},\"meta\":{}}"};
        for (String a : listBad) {
            answer = a;
            var e = assertThrows(KError.class, () -> crypto().listCrypts());
            assertEquals("K9O_INTERNAL_ERROR", e.getCode());
            assertTrue(e.getMessage().startsWith("crypto.crypts.list: the gateway answered "));
        }
        String[] bad = {CRYPT_JSON.replace("\"active\"", "\"gone\""), CRYPT_JSON.replace("\"createdAt\":\"2026-11-02T10:00:00Z\",", ""),
            CRYPT_JSON.replace("\"retiredAt\":null", "\"retiredAt\":5"), CRYPT_JSON.replace("\"name\":\"payments\",", ""), "null"};
        for (String d : bad) {
            answer = "{\"data\":" + d + ",\"meta\":{}}";
            var e = assertThrows(KError.class, () -> crypto().createCrypt("payments"));
            assertEquals("K9O_INTERNAL_ERROR", e.getCode());
            assertTrue(e.getMessage().startsWith("crypto.crypts.create: the gateway answered "));
        }
    }
}
