package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** keys(): method, path, query of list, get and rotate; the key fields; every rotate error. */
class KeysTest {

    record Call(String method, String path, String query, String body, String auth, String proof) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"data\":{},\"meta\":{}}";
    volatile String retryAfter;
    final List<String[]> queue = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8), ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("x-k9o-step-up-proof")));
            String[] scripted = queue.isEmpty() ? null : queue.remove(0);
            int st = scripted == null ? status : Integer.parseInt(scripted[0]);
            byte[] b = (scripted == null ? answer : scripted[1]).getBytes(StandardCharsets.UTF_8);
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

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").build());
    }

    static final String PLATFORM_KEY = "{\"id\":\"vkey_1\",\"version\":2,\"status\":\"active\",\"custodyMode\":\"PLATFORM_MANAGED\","
        + "\"createdAt\":\"2026-10-01T08:00:00.000Z\",\"rotatedAt\":\"2026-10-20T09:30:00.000Z\",\"byokRef\":null,\"hyokRef\":null}";
    static final String BYOK_KEY = "{\"id\":\"vkey_2\",\"version\":1,\"status\":\"revoked\",\"custodyMode\":\"BYOK\","
        + "\"createdAt\":\"2026-09-01T08:00:00.000Z\",\"rotatedAt\":null,"
        + "\"byokRef\":{\"provider\":\"aws-kms\",\"region\":\"eu-central-1\",\"externalKeyFingerprint\":\"AbCdEfGhIjKlMnOp\"},\"hyokRef\":null}";
    static final String HYOK_KEY = "{\"id\":\"vkey_3\",\"version\":4,\"status\":\"rotating\",\"custodyMode\":\"HYOK\","
        + "\"createdAt\":null,\"rotatedAt\":null,\"byokRef\":null,"
        + "\"hyokRef\":{\"clusterId\":\"hsm_1\",\"clusterName\":\"berlin-a\",\"externalKeyFingerprint\":\"QrStUvWxYz012345\"},\"newField\":true}";

    @Test
    void listDefaultsToTheActiveKey() {
        answer = "{\"data\":{\"items\":[" + PLATFORM_KEY + "],\"nextCursor\":null},\"meta\":{}}";
        var out = client().keys().list();
        var c = calls.get(0);
        assertEquals("GET", c.method());
        assertEquals("/@portal/vault/keys", c.path());
        assertNull(c.query());
        assertEquals("Bearer tok_secret", c.auth());
        assertEquals(1, out.data().items().size());
        var k = out.data().items().get(0);
        assertEquals("vkey_1", k.id());
        assertEquals(2, k.version());
        assertEquals("active", k.status());
        assertEquals("PLATFORM_MANAGED", k.custodyMode());
        assertEquals("2026-10-01T08:00:00.000Z", k.createdAt());
        assertEquals("2026-10-20T09:30:00.000Z", k.rotatedAt());
        assertNull(k.byokRef());
        assertNull(k.hyokRef());
    }

    @Test
    void listAllAddsTheStatusQueryAndDecodesEveryFieldAndCursor() {
        answer = "{\"data\":{\"items\":[" + PLATFORM_KEY + "," + BYOK_KEY + "," + HYOK_KEY + "],\"nextCursor\":\"next_1\"},\"meta\":{}}";
        var out = client().keys().list(true);
        assertEquals("status=all", calls.get(0).query());
        assertEquals(3, out.data().items().size(), "a nextCursor from the gateway is tolerated and not exposed");
        var byok = out.data().items().get(1);
        assertEquals("revoked", byok.status());
        assertNull(byok.rotatedAt());
        assertEquals("aws-kms", byok.byokRef().provider());
        assertEquals("eu-central-1", byok.byokRef().region());
        assertEquals("AbCdEfGhIjKlMnOp", byok.byokRef().externalKeyFingerprint());
        var hyok = out.data().items().get(2);
        assertEquals("HYOK", hyok.custodyMode());
        assertNull(hyok.createdAt());
        assertEquals("hsm_1", hyok.hyokRef().clusterId());
        assertEquals("berlin-a", hyok.hyokRef().clusterName());
        assertEquals("QrStUvWxYz012345", hyok.hyokRef().externalKeyFingerprint());
    }

    @Test
    void getReadsOneKey() {
        answer = "{\"data\":" + BYOK_KEY + ",\"meta\":{}}";
        var out = client().keys().get("vkey_2");
        var c = calls.get(0);
        assertEquals("GET", c.method());
        assertEquals("/@portal/vault/keys/vkey_2", c.path());
        assertEquals("BYOK", out.data().custodyMode());
        assertEquals("AbCdEfGhIjKlMnOp", out.data().byokRef().externalKeyFingerprint());
    }

    @Test
    void getNotFound() {
        status = 404;
        answer = "{\"code\":\"K9O_NOT_FOUND\",\"message\":\"no key\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> client().keys().get("vkey_x"));
        assertEquals("K9O_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
    }

    @Test
    void trustZoneRefusalIsTyped() {
        status = 403;
        answer = "{\"code\":\"K9O_TRUST_ZONE_VIOLATION\",\"message\":\"not for service tokens\",\"context\":{}}";
        assertEquals("K9O_TRUST_ZONE_VIOLATION", assertThrows(KError.class, () -> client().keys().list()).getCode());
    }

    @Test
    void rotateSendsAPostWithoutABodyAndDecodesTheAnswer() {
        answer = "{\"data\":{\"keyId\":\"vkey_1\",\"previousVersion\":2,\"newVersion\":3,\"status\":\"rotated\"},\"meta\":{}}";
        var out = client().keys().rotate("vkey_1");
        var c = calls.get(0);
        assertEquals("POST", c.method());
        assertEquals("/@portal/vault/keys/vkey_1/rotate", c.path());
        assertEquals("", c.body());
        assertEquals("vkey_1", out.data().keyId());
        assertEquals(2, out.data().previousVersion());
        assertEquals(3, out.data().newVersion());
        assertEquals("rotated", out.data().status());
    }

    @Test
    void rotateInFlightIsAConflictAndIsNotRetried() {
        status = 409;
        answer = "{\"code\":\"K9O_STATE_CONFLICT\",\"message\":\"a rotation is running\",\"context\":{\"reason\":\"rotation_in_flight\"}}";
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1"));
        assertEquals("K9O_STATE_CONFLICT", e.getCode());
        assertEquals(409, e.getStatus());
        assertEquals("rotation_in_flight", e.getContext().get("reason"));
        assertEquals(1, calls.size());
    }

    @Test
    void rotateRateCapGivesRetryAfterFromTheHeader() {
        status = 429;
        retryAfter = "3600";
        answer = "{\"code\":\"K9O_RATE_LIMITED\",\"message\":\"3 rotations in 24 hours\",\"context\":{\"reason\":\"rotation_rate_cap\"}}";
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1"));
        assertEquals("K9O_RATE_LIMITED", e.getCode());
        assertEquals(429, e.getStatus());
        assertEquals("rotation_rate_cap", e.getContext().get("reason"));
        assertEquals(3600.0, e.retryAfterSec());
        assertEquals(1, calls.size(), "a POST is not retried");
    }

    @Test
    void retryAfterInTheBodyWinsOverTheHeader() {
        status = 429;
        retryAfter = "3600";
        answer = "{\"code\":\"K9O_RATE_LIMITED\",\"message\":\"m\",\"context\":{},\"recommendation\":{\"action\":\"wait\",\"retryAfterSec\":12}}";
        assertEquals(12.0, assertThrows(KError.class, () -> client().keys().rotate("vkey_1")).retryAfterSec());
    }

    @Test
    void anHttpDateRetryAfterIsIgnored() {
        status = 429;
        retryAfter = "Wed, 21 Oct 2026 07:28:00 GMT";
        answer = "{\"code\":\"K9O_RATE_LIMITED\",\"message\":\"m\",\"context\":{}}";
        assertNull(assertThrows(KError.class, () -> client().keys().rotate("vkey_1")).retryAfterSec());
    }

    static final String CHALLENGE = "{\"code\":\"K9O_STEP_UP_REQUIRED\",\"message\":\"prove it\",\"context\":{\"stepUp\":{\"scopeSetRoot\":\"root_1\","
        + "\"nonce\":\"n_1\",\"minTier\":\"T3_KEY_POSSESSION\",\"methods\":[\"webauthn\",\"dpop\"],\"maxAgeSec\":120,\"singleUse\":true,\"rpId\":\"k9o.io\"}}}";
    static final String ROTATED = "{\"data\":{\"keyId\":\"vkey_1\",\"previousVersion\":2,\"newVersion\":3,\"status\":\"rotated\"},\"meta\":{}}";

    @Test
    void stepUpChallengeIsReachableOnTheErrorWithoutAHook() {
        status = 401;
        answer = CHALLENGE;
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1"));
        assertEquals("K9O_STEP_UP_REQUIRED", e.getCode());
        var ch = e.getStepUp();
        assertEquals("root_1", ch.scopeSetRoot());
        assertEquals("n_1", ch.nonce());
        assertEquals("T3_KEY_POSSESSION", ch.minTier());
        assertEquals(List.of("webauthn", "dpop"), ch.methods());
        assertEquals(120, ch.maxAgeSec());
        assertEquals("k9o.io", ch.rpId());
        assertEquals(1, calls.size());
        assertNull(calls.get(0).proof());
    }

    @Test
    void stepUpHookReceivesTheChallengeAndTheRetryCarriesTheProof() throws Exception {
        queue.add(new String[] {"401", CHALLENGE});
        queue.add(new String[] {"200", ROTATED});
        var seen = new java.util.ArrayList<Keys.StepUpRequest>();
        var out = client().keys().rotate("vkey_1", Keys.RotateOptions.withStepUp(req -> {
            seen.add(req);
            return new Keys.StepUpProof("dpop", "proof.jwt.value");
        }));
        assertEquals("rotated", out.data().status());
        assertEquals(2, calls.size());
        assertEquals(1, seen.size());
        assertEquals("POST", seen.get(0).method());
        assertEquals(baseUrl + "/@portal/vault/keys/vkey_1/rotate", seen.get(0).url());
        assertEquals("n_1", seen.get(0).challenge().nonce());
        assertNull(calls.get(0).proof());
        String h = calls.get(1).proof();
        assertFalse(h.contains("=") || h.contains("+") || h.contains("/"), "base64url without padding");
        var j = new com.fasterxml.jackson.databind.ObjectMapper().readTree(java.util.Base64.getUrlDecoder().decode(h));
        assertEquals("root_1", j.get("scopeSetRoot").asText());
        assertEquals("n_1", j.get("nonce").asText());
        assertEquals("dpop", j.get("method").asText());
        assertEquals("proof.jwt.value", j.get("proof").asText());
        assertEquals("/@portal/vault/keys/vkey_1/rotate", calls.get(1).path());
        assertEquals("", calls.get(1).body());
    }

    @Test
    void aSecondChallengeIsNotRetried() {
        queue.add(new String[] {"401", CHALLENGE});
        queue.add(new String[] {"401", "{\"code\":\"K9O_STEP_UP_PROOF_MALFORMED\",\"message\":\"bad proof\",\"context\":{}}"});
        var n = new java.util.concurrent.atomic.AtomicInteger();
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1", Keys.RotateOptions.withStepUp(req -> {
            n.incrementAndGet();
            return new Keys.StepUpProof("dpop", "p");
        })));
        assertEquals("K9O_STEP_UP_PROOF_MALFORMED", e.getCode());
        assertEquals(2, calls.size());
        assertEquals(1, n.get());
        assertFalse(e.getMessage().contains("proof.jwt"));
    }

    @Test
    void aSecondStepUpRequiredIsNotAnsweredAgain() {
        queue.add(new String[] {"401", CHALLENGE});
        queue.add(new String[] {"401", CHALLENGE});
        var n = new java.util.concurrent.atomic.AtomicInteger();
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1", Keys.RotateOptions.withStepUp(req -> {
            n.incrementAndGet();
            return new Keys.StepUpProof("webauthn", "p");
        })));
        assertEquals("K9O_STEP_UP_REQUIRED", e.getCode());
        assertEquals(2, calls.size());
        assertEquals(1, n.get());
    }

    @Test
    void aThrowingHookStopsTheRotation() {
        queue.add(new String[] {"401", CHALLENGE});
        var e = assertThrows(IllegalStateException.class, () -> client().keys().rotate("vkey_1",
            Keys.RotateOptions.withStepUp(req -> { throw new IllegalStateException("no hardware key"); })));
        assertEquals("no hardware key", e.getMessage());
        assertEquals(1, calls.size());
    }

    @Test
    void aMethodThatWasNotOfferedIsRefusedBeforeSending() {
        queue.add(new String[] {"401", CHALLENGE});
        var e = assertThrows(KError.class, () -> client().keys().rotate("vkey_1",
            Keys.RotateOptions.withStepUp(req -> new Keys.StepUpProof("mtls", "p"))));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        assertEquals(1, calls.size());
    }

    @Test
    void aHookIsNotCalledForOtherErrors() {
        status = 409;
        answer = "{\"code\":\"K9O_STATE_CONFLICT\",\"message\":\"m\",\"context\":{\"reason\":\"rotation_in_flight\"}}";
        var n = new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(KError.class, () -> client().keys().rotate("vkey_1", Keys.RotateOptions.withStepUp(req -> {
            n.incrementAndGet();
            return new Keys.StepUpProof("dpop", "p");
        })));
        assertEquals(0, n.get());
        assertEquals(1, calls.size());
    }

    @Test
    void checksArgumentsAndProjectContext() {
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().keys().get("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().keys().rotate(null)).getCode());
        var noProject = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> noProject.keys().list()).getCode());
        assertTrue(calls.isEmpty());
    }
}
