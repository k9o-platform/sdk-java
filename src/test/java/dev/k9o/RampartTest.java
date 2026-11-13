package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** rampart(): classify wire, jwks, and verifyReceipt with receipts signed in the test by a fresh Ed25519 key. */
class RampartTest {

    static final long NOW = 1_800_000_000L;
    static final Clock CLOCK = Clock.fixed(Instant.ofEpochSecond(NOW), ZoneOffset.UTC);
    static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();

    HttpServer server;
    String baseUrl;
    final List<String> requests = new CopyOnWriteArrayList<>(); // "METHOD target"
    final AtomicReference<String> body = new AtomicReference<>();
    final AtomicReference<String> auth = new AtomicReference<>();
    volatile int classifyStatus = 200;
    volatile String classifyAnswer = "{}";
    volatile String jwksAnswer = "{\"keys\":[]}";
    static final String ISS = "https://api.example.test/rampart";
    static final String PROJ = "proj_abc123";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            body.set(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            auth.set(ex.getRequestHeaders().getFirst("authorization"));
            boolean jwks = ex.getRequestURI().getPath().equals("/rampart/jwks");
            byte[] b = (jwks ? jwksAnswer : classifyAnswer).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(jwks ? 200 : classifyStatus, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void tearDown() {
        if (server != null) server.stop(0);
    }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").build());
    }

    // ---- a signer standing in for the gateway

    record Signer(KeyPair pair, String kid) {
        static Signer create(String kid) throws Exception {
            return new Signer(KeyPairGenerator.getInstance("Ed25519").generateKeyPair(), kid);
        }

        Rampart.Jwk jwk() {
            byte[] der = pair.getPublic().getEncoded();
            byte[] raw = Arrays.copyOfRange(der, der.length - 32, der.length);
            return new Rampart.Jwk(kid, "OKP", "Ed25519", B64.encodeToString(raw), "EdDSA", "sig");
        }

        String jwksJson() { return jwksJson(ISS); }

        String jwksJson(String issuer) {
            Rampart.Jwk k = jwk();
            return "{" + (issuer == null ? "" : "\"issuer\":\"" + issuer + "\",") + "\"keys\":[{\"crv\":\"Ed25519\",\"x\":\"" + k.x() + "\",\"kty\":\"OKP\",\"kid\":\"" + kid + "\",\"alg\":\"EdDSA\",\"use\":\"sig\",\"k9o_signer_role\":\"public\"}],\"meta\":{}}";
        }

        String sign(String headerJson, String claimsJson) throws Exception {
            String signing = B64.encodeToString(headerJson.getBytes(StandardCharsets.UTF_8)) + "."
                + B64.encodeToString(claimsJson.getBytes(StandardCharsets.UTF_8));
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(pair.getPrivate());
            s.update(signing.getBytes(StandardCharsets.US_ASCII));
            return signing + "." + B64.encodeToString(s.sign());
        }
    }

    static String digest(Object input) throws Exception {
        return Rampart.hex(MessageDigest.getInstance("SHA-256").digest(Jcs.canonicalBytes(input)));
    }

    static final Map<String, Object> INPUT = Map.of("text", "hello", "n", 1);

    String header(String kid) { return "{\"alg\":\"EdDSA\",\"kid\":\"" + kid + "\",\"typ\":\"k9o.rampart.classify.v1\"}"; }

    String claims(String iss, String project, long iat, long exp, String sha) {
        return "{\"iss\":\"" + iss + "\",\"iat\":" + iat + ",\"exp\":" + exp + ",\"input_sha256\":\"" + sha
            + "\",\"classification\":\"suspicious\",\"risk_bp\":6200,\"confidence_bp\":8100,\"categories\":[\"injection\"],"
            + "\"project_id\":\"" + project + "\",\"calibration_version\":\"cal-7\"}";
    }

    String good(Signer s) throws Exception {
        return s.sign(header(s.kid()), claims(ISS, PROJ, NOW - 5, NOW + 3600, digest(INPUT)));
    }

    Rampart.VerifyOptions opts(Signer s) {
        return Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).projectId(PROJ).build();
    }

    void refused(Rampart.Reason reason, String receipt, Rampart.VerifyOptions o) {
        var e = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(receipt, INPUT, o));
        assertEquals(reason, e.reason());
    }

    // ---- wire

    @Test
    void classifyWire() {
        classifyAnswer = "{\"data\":{\"classification\":\"suspicious\",\"riskScore\":6200,\"confidence\":8100,\"categories\":[\"injection\",\"x\"],"
            + "\"calibrationVersion\":\"cal-7\",\"classificationReceipt\":\"a.b.c\",\"projectId\":\"proj_x\",\"extra\":1},\"meta\":{}}";
        var r = client().rampart().classify(INPUT, "support-chat");
        assertEquals("POST " + "/@portal/rampart/classify", requests.get(0));
        assertEquals("Bearer tok_secret", auth.get());
        var sent = assertDoesNotThrow(() -> HttpTransport.JSON.readTree(body.get()));
        assertEquals("hello", sent.get("input").get("text").asText());
        assertEquals("support-chat", sent.get("label").asText());
        assertEquals("suspicious", r.classification());
        assertEquals(6200, r.riskScore());
        assertEquals(8100, r.confidence());
        assertEquals(List.of("injection", "x"), r.categories());
        assertEquals("cal-7", r.calibrationVersion());
        assertEquals("a.b.c", r.classificationReceipt());
        assertEquals("proj_x", r.projectId());
    }

    @Test
    void classifyWithoutLabelSendsNone() {
        classifyAnswer = "{\"data\":{\"classification\":\"clear\",\"riskScore\":0,\"confidence\":9900,\"categories\":[],\"calibrationVersion\":\"c\",\"classificationReceipt\":\"r\"},\"meta\":{}}";
        client().rampart().classify(INPUT);
        assertFalse(assertDoesNotThrow(() -> HttpTransport.JSON.readTree(body.get())).has("label"));
    }

    @Test
    void classifier503IsAnErrorNeverAVerdict() {
        classifyStatus = 503;
        classifyAnswer = "{\"code\":\"K9O_RAMPART_CLASSIFIER_UNAVAILABLE\",\"message\":\"down\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> client().rampart().classify(INPUT));
        assertEquals("K9O_RAMPART_CLASSIFIER_UNAVAILABLE", e.getCode());
        assertEquals(503, e.getStatus());
        assertEquals(1, requests.size(), "a POST is not retried");
    }

    @Test
    void inputTooLong413IsATypedErrorNeverAVerdict() {
        classifyStatus = 413;
        classifyAnswer = "{\"code\":\"K9O_RAMPART_INPUT_TOO_LONG\",\"message\":\"too long\",\"context\":{\"reason\":\"rampart_input_too_long\"}}";
        var e = assertThrows(KError.class, () -> client().rampart().classify(INPUT));
        assertEquals(Rampart.INPUT_TOO_LONG, e.getCode());
        assertEquals("K9O_RAMPART_INPUT_TOO_LONG", e.getCode());
        assertEquals(413, e.getStatus());
        assertEquals("rampart_input_too_long", e.getContext().get("reason"));
        assertEquals(1, requests.size(), "a POST is not retried");
    }

    @Test
    void windowsClaimIsExposedAsANumber() throws Exception {
        var s = Signer.create("k1");
        String c = claims(ISS, PROJ, NOW - 5, NOW + 3600, digest(INPUT));
        String withWindows = c.substring(0, c.length() - 1) + ",\"windows\":3}";
        var v = client().rampart().verifyReceipt(s.sign(header("k1"), withWindows), INPUT, opts(s));
        assertEquals(3, v.windows());
        assertEquals("suspicious", v.classification());
    }

    @Test
    void aReceiptWithoutWindowsReadsAsOneWindow() throws Exception {
        var s = Signer.create("k1");
        assertEquals(1, client().rampart().verifyReceipt(good(s), INPUT, opts(s)).windows());
    }

    @Test
    void unknownClaimsAreTolerated() throws Exception {
        var s = Signer.create("k1");
        String c = claims(ISS, PROJ, NOW - 5, NOW + 3600, digest(INPUT));
        String extra = c.substring(0, c.length() - 1) + ",\"windows\":1,\"future_claim\":{\"a\":[1,2]},\"another\":\"x\"}";
        var v = client().rampart().verifyReceipt(s.sign(header("k1"), extra), INPUT, opts(s));
        assertEquals(1, v.windows());
        assertEquals(6200, v.riskBp());
    }

    @Test
    void aWindowsClaimThatIsNotAWholeNumberIsMalformed() throws Exception {
        var s = Signer.create("k1");
        String c = claims(ISS, PROJ, NOW - 5, NOW + 3600, digest(INPUT));
        for (String bad : List.of("\"3\"", "0", "2.5", "-1", "null")) {
            String claims = c.substring(0, c.length() - 1) + ",\"windows\":" + bad + "}";
            refused(Rampart.Reason.MALFORMED, s.sign(header("k1"), claims), opts(s));
        }
    }

    @Test
    void classifyRefusesAnInputThatIsNotAJsonObject() throws Exception {
        Object[] bad = {"just a string", 42, true, List.of("a"), null,
            new com.fasterxml.jackson.databind.ObjectMapper().readTree("\"text\""),
            new com.fasterxml.jackson.databind.ObjectMapper().readTree("[1]")};
        for (Object o : bad) {
            var e = assertThrows(KError.class, () -> client().rampart().classify(o, null));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
            assertEquals("input", e.getContext().get("field"));
        }
        assertTrue(requests.isEmpty(), "nothing was sent");
    }

    @Test
    void classifyChecksArguments() {
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().rampart().classify(null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().rampart().classify(INPUT, "")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().rampart().classify(INPUT, "x".repeat(129))).getCode());
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.rampart().classify(INPUT)).getCode());
        assertTrue(requests.isEmpty());
    }

    @Test
    void jwksWire() throws Exception {
        jwksAnswer = Signer.create("k1").jwksJson();
        var set = client().rampart().jwks();
        assertEquals("GET /rampart/jwks", requests.get(0));
        assertNull(auth.get(), "the key feed is public: no credential is sent");
        assertEquals(1, set.keys().size());
        assertEquals(ISS, set.issuer());
        assertEquals("k1", set.keys().get(0).kid());
        assertEquals("OKP", set.keys().get(0).kty());
        assertEquals("Ed25519", set.keys().get(0).crv());
    }

    @Test
    void jwksWithoutIssuerHasNullIssuer() throws Exception {
        jwksAnswer = Signer.create("k1").jwksJson(null);
        assertNull(client().rampart().jwks().issuer());
    }

    // ---- verifyReceipt

    @Test
    void happyPathReadsTheVerdictFromTheClaims() throws Exception {
        var s = Signer.create("k1");
        var v = client().rampart().verifyReceipt(good(s), INPUT, opts(s));
        assertEquals("suspicious", v.classification());
        assertEquals(6200, v.riskBp());
        assertEquals(8100, v.confidenceBp());
        assertEquals(List.of("injection"), v.categories());
        assertEquals(PROJ, v.projectId());
        assertEquals("cal-7", v.calibrationVersion());
        assertEquals("k1", v.kid());
        assertEquals(NOW + 3600, v.expiresAt());
        assertTrue(requests.isEmpty(), "an injected key set means no request");
    }

    @Test
    void keyOrderOfTheInputDoesNotMatter() throws Exception {
        var s = Signer.create("k1");
        var reordered = new LinkedHashMap<String, Object>();
        reordered.put("text", "hello");
        reordered.put("n", 1);
        assertDoesNotThrow(() -> client().rampart().verifyReceipt(good(s), reordered, opts(s)));
    }

    @Test
    void wrongKid() throws Exception {
        var s = Signer.create("k1");
        var other = Signer.create("k2");
        refused(Rampart.Reason.KEY_UNKNOWN, other.sign(header("k2"), claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT))), opts(s));
    }

    @Test
    void aKeyCarriedInTheReceiptIsNeverUsed() throws Exception {
        var s = Signer.create("k1");
        var attacker = Signer.create("k1"); // same kid, different key: the signature cannot verify under the feed's key
        refused(Rampart.Reason.SIGNATURE, attacker.sign(header("k1"), claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT))), opts(s));
        refused(Rampart.Reason.MALFORMED, s.sign("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"typ\":\"k9o.rampart.classify.v1\",\"jwk\":{}}",
            claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT))), opts(s));
    }

    @Test
    void wrongAlg() throws Exception {
        var s = Signer.create("k1");
        String c = claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT));
        refused(Rampart.Reason.ALG, s.sign("{\"alg\":\"HS256\",\"kid\":\"k1\",\"typ\":\"k9o.rampart.classify.v1\"}", c), opts(s));
        refused(Rampart.Reason.ALG, s.sign("{\"alg\":\"none\",\"kid\":\"k1\",\"typ\":\"k9o.rampart.classify.v1\"}", c), opts(s));
    }

    @Test
    void wrongTyp() throws Exception {
        var s = Signer.create("k1");
        String c = claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT));
        refused(Rampart.Reason.TYP, s.sign("{\"alg\":\"EdDSA\",\"kid\":\"k1\",\"typ\":\"JWT\"}", c), opts(s));
        refused(Rampart.Reason.TYP, s.sign("{\"alg\":\"EdDSA\",\"kid\":\"k1\"}", c), opts(s));
    }

    @Test
    void wrongIssuer() throws Exception {
        var s = Signer.create("k1");
        refused(Rampart.Reason.ISSUER, s.sign(header("k1"), claims("https://elsewhere.example/rampart", PROJ, NOW, NOW + 60, digest(INPUT))), opts(s));
    }

    @Test
    void issuerOptionOverridesTheFeed() throws Exception {
        var s = Signer.create("k1");
        String other = "https://elsewhere.example/rampart";
        String r = s.sign(header("k1"), claims(other, PROJ, NOW, NOW + 60, digest(INPUT)));
        var o = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).projectId(PROJ).issuer(other).build();
        assertDoesNotThrow(() -> client().rampart().verifyReceipt(r, INPUT, o));
        refused(Rampart.Reason.ISSUER, good(s), o); // the option wins, so the feed's own issuer is now the wrong one
    }

    @Test
    void feedWithoutIssuerAndNoOptionIsRefused() throws Exception {
        var s = Signer.create("k1");
        var o = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()))).clock(CLOCK).projectId(PROJ).build();
        var e = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(good(s), INPUT, o));
        assertEquals(Rampart.Reason.ISSUER, e.reason());
        assertTrue(e.getMessage().contains("no issuer is advertised"));
        jwksAnswer = s.jwksJson(null); // a fetched feed without an issuer: same
        var e2 = assertThrows(Rampart.ReceiptRefusal.class,
            () -> client().rampart().verifyReceipt(good(s), INPUT, Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build()));
        assertEquals(Rampart.Reason.ISSUER, e2.reason());
        assertDoesNotThrow(() -> client().rampart().verifyReceipt(good(s), INPUT,
            Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).issuer(ISS).build()));
    }

    @Test
    void issuerComesFromTheFetchedFeed() throws Exception {
        var s = Signer.create("k1");
        jwksAnswer = s.jwksJson();
        var v = client().rampart().verifyReceipt(good(s), INPUT, Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build());
        assertEquals(ISS, v.issuer());
    }

    @Test
    void wrongProject() throws Exception {
        var s = Signer.create("k1");
        refused(Rampart.Reason.PROJECT, s.sign(header("k1"), claims(ISS, "proj_other", NOW, NOW + 60, digest(INPUT))), opts(s));
    }

    @Test
    void bareReceiptWithoutProjectIdIsProjectUnknown() throws Exception {
        var s = Signer.create("k1");
        var o = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).build();
        refused(Rampart.Reason.PROJECT_UNKNOWN, good(s), o);
    }

    @Test
    void classifyResultCarriesItsProjectId() throws Exception {
        var s = Signer.create("k1");
        var o = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).build();
        var result = new Rampart.Classification("suspicious", 6200, 8100, List.of("injection"), "cal-7", good(s), PROJ);
        assertEquals(PROJ, client().rampart().verifyReceipt(result, INPUT, o).projectId());
        var wrong = new Rampart.Classification("suspicious", 6200, 8100, List.of("injection"), "cal-7", good(s), "proj_other");
        var e = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(wrong, INPUT, o));
        assertEquals(Rampart.Reason.PROJECT, e.reason());
        var none = new Rampart.Classification("suspicious", 6200, 8100, List.of("injection"), "cal-7", good(s), null);
        var e2 = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(none, INPUT, o));
        assertEquals(Rampart.Reason.PROJECT_UNKNOWN, e2.reason());
        var o2 = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).projectId("proj_other").build();
        var e3 = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(result, INPUT, o2));
        assertEquals(Rampart.Reason.PROJECT, e3.reason()); // the option wins over the carried id
    }

    @Test
    void theClientSlugIsNeverTheProject() throws Exception {
        var s = Signer.create("k1");
        var o = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).build();
        refused(Rampart.Reason.PROJECT_UNKNOWN, s.sign(header("k1"), claims(ISS, PROJ, NOW, NOW + 60, digest(INPUT))), o);
    }

    @Test
    void inputsTheProfileCannotExpressAreInputHash() throws Exception {
        var s = Signer.create("k1");
        String r = good(s);
        for (Object bad : List.of(Map.of("n", 1.5), Map.of("n", 9007199254740992L), Map.of("n", Double.NaN))) {
            var e = assertThrows(Rampart.ReceiptRefusal.class, () -> client().rampart().verifyReceipt(r, bad, opts(s)));
            assertEquals(Rampart.Reason.INPUT_HASH, e.reason());
            assertTrue(e.getMessage().contains("cannot be canonicalised"));
        }
    }

    @Test
    void classifyDoesNotPreValidateTheInput() {
        classifyAnswer = "{\"data\":{\"classification\":\"clear\",\"riskScore\":0,\"confidence\":9900,\"categories\":[],\"calibrationVersion\":\"c\",\"classificationReceipt\":\"r\"},\"meta\":{}}";
        client().rampart().classify(Map.of("n", 1.5));
        assertEquals(1, requests.size());
    }

    @Test
    void expiredAndNotYetValidWithSkew() throws Exception {
        var s = Signer.create("k1");
        String iss = ISS;
        refused(Rampart.Reason.EXPIRED, s.sign(header("k1"), claims(iss, PROJ, NOW - 7200, NOW - 61, digest(INPUT))), opts(s));
        assertDoesNotThrow(() -> client().rampart().verifyReceipt(
            s.sign(header("k1"), claims(iss, PROJ, NOW - 7200, NOW - 59, digest(INPUT))), INPUT, opts(s)), "inside the 60 s skew");
        refused(Rampart.Reason.NOT_YET_VALID, s.sign(header("k1"), claims(iss, PROJ, NOW + 61, NOW + 3600, digest(INPUT))), opts(s));
        var tight = Rampart.VerifyOptions.builder().keys(new Rampart.Jwks(List.of(s.jwk()), ISS)).clock(CLOCK).projectId(PROJ).clockSkewSeconds(0).build();
        refused(Rampart.Reason.EXPIRED, s.sign(header("k1"), claims(iss, PROJ, NOW - 7200, NOW - 1, digest(INPUT))), tight);
    }

    @Test
    void inputHashMismatch() throws Exception {
        var s = Signer.create("k1");
        var e = assertThrows(Rampart.ReceiptRefusal.class,
            () -> client().rampart().verifyReceipt(good(s), Map.of("text", "goodbye", "n", 1), opts(s)));
        assertEquals(Rampart.Reason.INPUT_HASH, e.reason());
    }

    @Test
    void tamperedSignatureAndClaims() throws Exception {
        var s = Signer.create("k1");
        String r = good(s);
        String[] p = r.split("\\.");
        byte[] sig = Base64.getUrlDecoder().decode(p[2]);
        sig[0] ^= 1;
        refused(Rampart.Reason.SIGNATURE, p[0] + "." + p[1] + "." + B64.encodeToString(sig), opts(s));
        refused(Rampart.Reason.SIGNATURE, p[0] + "." + p[1] + "." + B64.encodeToString(new byte[10]), opts(s));
        String forged = new String(Base64.getUrlDecoder().decode(p[1]), StandardCharsets.UTF_8).replace("suspicious", "clear");
        refused(Rampart.Reason.SIGNATURE, p[0] + "." + B64.encodeToString(forged.getBytes(StandardCharsets.UTF_8)) + "." + p[2], opts(s));
    }

    @Test
    void malformed() throws Exception {
        var s = Signer.create("k1");
        refused(Rampart.Reason.MALFORMED, "abc", opts(s));
        refused(Rampart.Reason.MALFORMED, "a.b", opts(s));
        refused(Rampart.Reason.MALFORMED, "a.b.c.d", opts(s));
        refused(Rampart.Reason.MALFORMED, "!!.!!.!!", opts(s));
        refused(Rampart.Reason.MALFORMED, null, opts(s));
        refused(Rampart.Reason.MALFORMED, B64.encodeToString("[]".getBytes()) + "." + B64.encodeToString("{}".getBytes()) + ".AAAA", opts(s));
        // missing claim after a good signature
        refused(Rampart.Reason.MALFORMED, s.sign(header("k1"), "{\"iss\":\"" + ISS + "\",\"project_id\":\"" + PROJ + "\",\"iat\":" + NOW + ",\"exp\":" + (NOW + 60) + "}"), opts(s));
    }

    @Test
    void closedReasonSet() {
        List<String> wires = new ArrayList<>();
        for (var r : Rampart.Reason.values()) wires.add(r.wire());
        assertEquals(List.of("malformed", "key_unknown", "alg", "typ", "signature", "issuer", "project", "expired", "not_yet_valid", "input_hash", "project_unknown"), wires);
    }

    // ---- key feed

    @Test
    void fetchesTheFeedItselfAndCachesIt() throws Exception {
        var s = Signer.create("k1");
        jwksAnswer = s.jwksJson();
        var c = client();
        var o = Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build();
        assertEquals("suspicious", c.rampart().verifyReceipt(good(s), INPUT, o).classification());
        assertEquals("suspicious", c.rampart().verifyReceipt(good(s), INPUT, o).classification());
        assertEquals(List.of("GET /rampart/jwks"), requests, "one fetch for the client's life");
    }

    @Test
    void unknownKidRefetchesOnceThenRefuses() throws Exception {
        var s1 = Signer.create("k1");
        var s2 = Signer.create("k2");
        jwksAnswer = s1.jwksJson();
        var c = client();
        var o = Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build();
        c.rampart().verifyReceipt(good(s1), INPUT, o);
        assertEquals(1, requests.size());
        // k2 appears after a rotation: one refetch finds it
        jwksAnswer = s2.jwksJson();
        assertDoesNotThrow(() -> c.rampart().verifyReceipt(good(s2), INPUT, o));
        assertEquals(2, requests.size());
        // a kid that never appears: exactly one refetch, then the refusal
        var s3 = Signer.create("k3");
        var e = assertThrows(Rampart.ReceiptRefusal.class, () -> c.rampart().verifyReceipt(good(s3), INPUT, o));
        assertEquals(Rampart.Reason.KEY_UNKNOWN, e.reason());
        assertEquals(3, requests.size());
    }

    @Test
    void firstUnknownKidCostsOneFetchNotTwo() throws Exception {
        jwksAnswer = Signer.create("k1").jwksJson();
        var s2 = Signer.create("k2");
        var e = assertThrows(Rampart.ReceiptRefusal.class,
            () -> client().rampart().verifyReceipt(good(s2), INPUT, Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build()));
        assertEquals(Rampart.Reason.KEY_UNKNOWN, e.reason());
        assertEquals(1, requests.size());
    }

    @Test
    void anUnreachableFeedIsAnErrorNotAPass() throws Exception {
        var s = Signer.create("k1");
        String receipt = good(s);
        var dead = K9o.createClient(K9o.Opts.builder().baseUrl("http://127.0.0.1:1").projectSlug("portal").build());
        assertThrows(KError.class, () -> dead.rampart().verifyReceipt(receipt, INPUT, Rampart.VerifyOptions.builder().clock(CLOCK).projectId(PROJ).build()));
    }
}
