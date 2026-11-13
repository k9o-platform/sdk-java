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
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** realtime().replay(): path, method, body, validation, decoding and errors against a local server. */
class RealtimeReplayTest {

    static final ObjectMapper JSON = new ObjectMapper();

    HttpServer server;
    String baseUrl;
    final List<String> paths = new CopyOnWriteArrayList<>();
    final List<String> methods = new CopyOnWriteArrayList<>();
    final List<String> bodies = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"data\":{\"topic\":\"collections.orders.*\",\"events\":[],\"nextCursor\":null,\"hasMore\":false},\"meta\":{}}";

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            paths.add(ex.getRequestURI().getRawPath());
            methods.add(ex.getRequestMethod());
            bodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    Realtime realtime(String slug) {
        K9o.Opts.Builder b = K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret");
        if (slug != null) b.projectSlug(slug);
        return K9o.createClient(b.build()).realtime();
    }

    @Test
    void sendsTopicAndOptionsToTheProjectRoute() throws Exception {
        realtime("portal").replay("collections.orders.*", new Realtime.ReplayOptions(5L, 9L, 50));
        assertEquals(List.of("/@portal/realtime/replay"), paths);
        assertEquals(List.of("POST"), methods);
        JsonNode body = JSON.readTree(bodies.get(0));
        assertEquals("collections.orders.*", body.get("topic").asText());
        assertEquals(5, body.get("fromSequence").asLong());
        assertEquals(9, body.get("untilSequence").asLong());
        assertEquals(50, body.get("limit").asInt());
        assertEquals(4, body.size());
    }

    @Test
    void sendsOnlyTheTopicWhenNoOptions() throws Exception {
        realtime("portal").replay("studio.workflows.runs.*");
        JsonNode body = JSON.readTree(bodies.get(0));
        assertEquals(1, body.size());
        assertEquals("studio.workflows.runs.*", body.get("topic").asText());
    }

    @Test
    void decodesEventsInTheSubscribeShape() {
        answer = "{\"data\":{\"topic\":\"collections.orders.*\",\"events\":["
            + "{\"sequence\":\"7\",\"topic\":\"collections.orders.insert\",\"eventType\":\"row_inserted\","
            + "\"payload\":{\"collection\":\"orders\",\"id\":\"rec1\"},\"occurredAt\":\"2026-11-13T10:00:00.000Z\",\"eventId\":\"evt_a1\"},"
            + "{\"sequence\":8,\"topic\":\"collections.orders.delete\",\"eventType\":\"row_deleted\","
            + "\"payload\":{\"collection\":\"orders\",\"id\":\"rec2\"},\"occurredAt\":\"2026-11-13T10:00:01.000Z\"}"
            + "],\"nextCursor\":\"8\",\"hasMore\":true},\"meta\":{}}";
        Realtime.ReplayPage page = realtime("portal").replay("collections.orders.*").data();
        assertEquals(2, page.events().size());
        Realtime.RealtimeEvent e = page.events().get(0);
        assertEquals("collections.orders.insert", e.topic());
        assertEquals("row_inserted", e.eventType());
        assertEquals("evt_a1", e.eventId());
        assertEquals("7", e.sequence());
        assertEquals("2026-11-13T10:00:00.000Z", e.occurredAt());
        assertEquals("rec1", ((java.util.Map<?, ?>) e.payload()).get("id"));
        assertEquals("8", page.events().get(1).sequence());
        assertNull(page.events().get(1).eventId());
        assertEquals("8", page.nextCursor());
        assertTrue(page.hasMore());
        assertNull(page.resync());
    }

    @Test
    void passesTheResyncHint() {
        answer = "{\"data\":{\"topic\":\"collections.orders.*\",\"events\":[],\"nextCursor\":null,\"hasMore\":false,\"resync\":\"reread-source\"},\"meta\":{}}";
        Realtime.ReplayPage page = realtime("portal").replay("collections.orders.*").data();
        assertTrue(page.events().isEmpty());
        assertEquals("reread-source", page.resync());
        assertNull(page.nextCursor());
    }

    @Test
    void refusesBadInputBeforeSending() {
        Realtime r = realtime("portal");
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> r.replay(null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> r.replay("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> r.replay("x".repeat(201))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> r.replay("t", new Realtime.ReplayOptions(null, null, 0))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> r.replay("t", new Realtime.ReplayOptions(null, null, 10_001))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> r.replay("t", new Realtime.ReplayOptions(-1L, null, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> r.replay("t", new Realtime.ReplayOptions(null, -1L, null))).getCode());
        assertTrue(paths.isEmpty());
    }

    @Test
    void acceptsTheLimitBounds() {
        realtime("portal").replay("t", new Realtime.ReplayOptions(null, null, 1));
        realtime("portal").replay("t", new Realtime.ReplayOptions(null, null, 10_000));
        assertEquals(2, paths.size());
    }

    @Test
    void needsAProjectSlug() {
        KError e = assertThrows(KError.class, () -> realtime(null).replay("t"));
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
        assertTrue(paths.isEmpty());
    }

    @Test
    void raisesGatewayErrors() {
        status = 403;
        answer = "{\"code\":\"K9O_RBAC_DENY\",\"message\":\"no\",\"context\":{}}";
        KError e = assertThrows(KError.class, () -> realtime("portal").replay("audit.project.*"));
        assertEquals("K9O_RBAC_DENY", e.getCode());
        assertEquals(403, e.getStatus());
    }

    @Test
    void refusesAnAnswerWithoutAnEventList() {
        answer = "{\"data\":{\"topic\":\"t\"},\"meta\":{}}";
        assertEquals("K9O_INTERNAL_ERROR", assertThrows(KError.class, () -> realtime("portal").replay("t")).getCode());
    }
}
