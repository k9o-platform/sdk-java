package dev.k9o;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/** A gateway error decodes the same whatever JSON type its recommendation has. */
class RecommendationDecodeTest {

    HttpServer server;
    String baseUrl;
    volatile int status = 403;
    volatile String body = "";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            ex.getRequestBody().readAllBytes();
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
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
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
    }

    static String wire(String rec) {
        return "{\"code\":\"K9O_X\",\"message\":\"m\",\"context\":{\"x\":1},\"recommendation\":" + rec + "}";
    }

    interface Call { void run(); }

    void check(String rec, String expectRec, Double expectRetry) {
        status = 403;
        body = wire(rec);
        Call[] paths = {
            () -> client().projects().list(),
            () -> {
                K9o.K9oClient c = client();
                try (Stream<String> s = HttpTransport.openSseLines(c.config, "GET", "/events", null, Map.of())) {
                    s.count();
                }
            },
        };
        for (Call p : paths) {
            KError e = assertThrows(KError.class, p::run);
            assertEquals("K9O_X", e.getCode());
            assertEquals("m", e.getMessage());
            assertEquals(403, e.getStatus());
            assertEquals(1, ((Number) e.getContext().get("x")).intValue());
            assertEquals(expectRec, e.getRecommendation());
            assertEquals(expectRetry, e.retryAfterSec());
        }
    }

    @Test
    void objectWithAction() {
        check("{\"action\":\"Open Billing and settle the outstanding wallet balance to lift the suspension.\"}",
            "Open Billing and settle the outstanding wallet balance to lift the suspension.", null);
    }

    @Test
    void objectWithRetryAfterAndNote() {
        check("{\"retryAfterSec\":30,\"note\":\"retry\"}", "retry", 30.0);
    }

    @Test
    void nullRecommendation() { check("null", null, null); }

    @Test
    void stringRecommendation() { check("\"do this\"", "do this", null); }

    @Test
    void numberRecommendation() { check("42", null, null); }

    @Test
    void arrayRecommendation() { check("[\"a\",{\"action\":\"b\"}]", null, null); }

    @Test
    void actionWinsOverNoteAndBadRetryIsNull() {
        check("{\"action\":\"a\",\"note\":\"n\",\"retryAfterSec\":-1}", "a", null);
        check("{\"note\":5,\"retryAfterSec\":\"3\"}", null, null);
    }

    @Test
    void retryAfterOnTooManyRequests() {
        K9o.K9oClient c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl)
            .retryConfig(new RetryConfig(0, "none", 1, 1)).build());
        status = 429;
        body = wire("{\"retryAfterSec\":30,\"note\":\"retry\"}");
        KError e = assertThrows(KError.class, () -> c.projects().list());
        assertEquals(429, e.getStatus());
        assertEquals("retry", e.getRecommendation());
        assertEquals(30.0, e.retryAfterSec());
    }

    @Test
    void sixArgConstructorAndWithRecommendationKeepRetry() {
        assertNull(new KError("A", "b", Map.of(), null, null, null).retryAfterSec());
        KError e = new KError("A", "b", Map.of(), null, 429, null, 2.5).withRecommendation("r");
        assertEquals(2.5, e.retryAfterSec());
        assertEquals("r", e.getRecommendation());
    }
}
