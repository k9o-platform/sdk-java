package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** custody().status: method, path and decoded answers, including a missing hsm and the error statuses. */
class CustodyTest {

    HttpServer server;
    String baseUrl;
    final AtomicReference<String> method = new AtomicReference<>();
    final AtomicReference<String> target = new AtomicReference<>();
    volatile int status = 200;
    volatile String answer;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            method.set(ex.getRequestMethod());
            target.set(ex.getRequestURI().toString());
            ex.getRequestBody().readAllBytes();
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
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
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build());
    }

    @Test
    void platformManagedWithoutHsm() {
        answer = "{\"data\":{\"mode\":\"PLATFORM_MANAGED\",\"attached\":false,\"provider\":null,\"lastJob\":null},\"meta\":{}}";
        var s = client().custody().status().data();
        assertEquals("GET", method.get());
        assertEquals("/@portal/custody/status", target.get());
        assertEquals("PLATFORM_MANAGED", s.mode());
        assertFalse(s.attached());
        assertNull(s.provider());
        assertNull(s.lastJob());
        assertNull(s.hsm());
    }

    @Test
    void hyokWithHsm() {
        answer = "{\"data\":{\"mode\":\"HYOK\",\"attached\":true,\"provider\":\"hsm\",\"lastJob\":null,\"hsm\":{\"vendor\":\"aws-cloudhsm\","
            + "\"clusterId\":\"cluster-1234567890abcdef\",\"region\":\"de01\",\"attestation\":{\"status\":null,\"verifiedAt\":null},\"keys\":null}},\"meta\":{}}";
        var s = client().custody().status().data();
        assertEquals("HYOK", s.mode());
        assertTrue(s.attached());
        assertEquals("hsm", s.provider());
        assertEquals("aws-cloudhsm", s.hsm().vendor());
        assertEquals("cluster-1234567890abcdef", s.hsm().clusterId());
        assertEquals("de01", s.hsm().region());
        assertNull(s.hsm().attestation().status());
        assertNull(s.hsm().attestation().verifiedAt());
        assertNull(s.hsm().keys());
    }

    @Test
    void lastJobDecodes() {
        answer = "{\"data\":{\"mode\":\"BYOK\",\"attached\":true,\"provider\":\"external_kms\","
            + "\"lastJob\":{\"kind\":\"external_transition\",\"status\":\"completed\",\"completedAt\":\"2026-10-26T12:00:00.000Z\"}},\"meta\":{}}";
        var j = client().custody().status().data().lastJob();
        assertEquals("external_transition", j.kind());
        assertEquals("completed", j.status());
        assertEquals("2026-10-26T12:00:00.000Z", j.completedAt());
    }

    @Test
    void errorStatusesSurface() {
        status = 403;
        answer = "{\"code\":\"K9O_RBAC_DENY\",\"message\":\"no\",\"context\":{}}";
        assertEquals("K9O_RBAC_DENY", assertThrows(KError.class, () -> client().custody().status()).getCode());
        status = 404;
        answer = "{\"code\":\"K9O_NOT_FOUND\",\"message\":\"Project not found\",\"context\":{}}";
        assertEquals("K9O_NOT_FOUND", assertThrows(KError.class, () -> client().custody().status()).getCode());
    }

    @Test
    void needsAProjectSlug() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.custody().status()).getCode());
    }
}
