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
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** metadata(): method, path, query and body of every call, prefixing, validation, error decoding and the change feed. */
class MetadataTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record Call(String method, String path, String query, String body, String lastEventId) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"data\":[],\"meta\":{}}";

    final List<String> feedScript = new CopyOnWriteArrayList<>();
    final AtomicInteger feedHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                body, ex.getRequestHeaders().getFirst("Last-Event-ID")));
            if (ex.getRequestURI().getPath().endsWith("/metadata/subscribe")) {
                int n = feedHits.getAndIncrement();
                String b = feedScript.get(Math.min(n, feedScript.size() - 1));
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) { os.write(b.getBytes(StandardCharsets.UTF_8)); }
                return;
            }
            byte[] b = answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    Metadata metadata() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build()).metadata();
    }

    Call only() {
        assertEquals(1, calls.size());
        return calls.get(0);
    }

    JsonNode body(Call c) throws IOException { return JSON.readTree(c.body()); }

    static final String ROWS = "{\"data\":[{\"surface\":\"collection:posts\",\"scope\":\"posts\",\"recordId\":\"p1\","
        + "\"metadata\":{\"title\":\"Quarterly report\",\"tags\":[\"pinned\",\"finance\"],\"priority\":3,\"embedding\":\"[1,0,0]\"}},"
        + "{\"surface\":\"telemetry:collection.metadata_index_declared\",\"scope\":\"collection.metadata_index_declared\","
        + "\"eventId\":\"ev1\",\"metadata\":{\"type\":\"collection.metadata_index_declared\",\"actorId\":\"usr_1\"}}],"
        + "\"meta\":{\"requestId\":\"r1\",\"cursor\":{\"next\":\"c2\"}}}";

    @Test
    void filterSendsPrefixedWhereAndDecodesRowsAndCursor() throws Exception {
        answer = ROWS;
        var where = List.of(new Metadata.Where("priority", "gte", 3), new Metadata.Where("metadata.title", "startsWith", "Q"));
        var out = metadata().filter(new Metadata.FilterInput(List.of("collection:posts"), where, 25, "c1"));
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/metadata/query", c.path());
        assertNull(c.query());
        var b = body(c);
        assertEquals("collection:posts", b.get("surfaces").get(0).asText());
        assertEquals("metadata.priority", b.get("where").get(0).get("field").asText());
        assertEquals("gte", b.get("where").get(0).get("op").asText());
        assertEquals(3, b.get("where").get(0).get("value").asInt());
        assertEquals("metadata.title", b.get("where").get(1).get("field").asText());
        assertEquals(25, b.get("limit").asInt());
        assertEquals("c1", b.get("cursor").asText());
        assertEquals("c2", out.meta().cursor().next());
        var rows = out.data();
        assertEquals("p1", rows.get(0).recordId());
        assertNull(rows.get(0).eventId());
        assertEquals("Quarterly report", rows.get(0).metadata().get("title"));
        assertEquals("ev1", rows.get(1).eventId());
        assertNull(rows.get(1).recordId());
    }

    @Test
    void filterMinimalBodyAndNullCursor() throws Exception {
        answer = "{\"data\":[],\"meta\":{\"cursor\":{\"next\":null}}}";
        var out = metadata().filter(Metadata.FilterInput.of("collection:posts", "collection:notes"));
        var b = body(only());
        assertEquals(2, b.get("surfaces").size());
        assertFalse(b.has("where"));
        assertFalse(b.has("limit"));
        assertFalse(b.has("cursor"));
        assertNull(out.meta().cursor().next());
    }

    @Test
    void telemetryKeepsBareCreatedAtAndActorId() throws Exception {
        answer = ROWS;
        var where = List.of(new Metadata.Where("createdAt", "gt", "2026-10-01T00:00:00Z"), new Metadata.Where("actorId", "eq", "usr_1"));
        metadata().filter(new Metadata.FilterInput(List.of("telemetry:collection.metadata_index_declared"), where, null, null));
        var b = body(only());
        assertEquals("createdAt", b.get("where").get(0).get("field").asText());
        assertEquals("actorId", b.get("where").get(1).get("field").asText());
    }

    @Test
    void createdAtIsPrefixedWithoutATelemetrySurface() throws Exception {
        metadata().filter(new Metadata.FilterInput(List.of("collection:posts"), List.of(new Metadata.Where("createdAt", "gt", "x")), null, null));
        assertEquals("metadata.createdAt", body(only()).get("where").get(0).get("field").asText());
    }

    @Test
    void filterValidationSendsNothing() {
        var m = metadata();
        var w = List.of(new Metadata.Where("a", "eq", 1));
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> m.filter(null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> m.filter(new Metadata.FilterInput(List.of(), null, null, null))).getCode());
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.filter(Metadata.FilterInput.of("posts"))).getCode());
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.filter(Metadata.FilterInput.of("table:posts"))).getCode());
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.filter(Metadata.FilterInput.of("collection:1bad"))).getCode());
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.filter(Metadata.FilterInput.of("storage:"))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> m.filter(new Metadata.FilterInput(List.of("collection:posts"), w, 0, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> m.filter(new Metadata.FilterInput(List.of("collection:posts"), w, 1001, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> m.filter(new Metadata.FilterInput(List.of("collection:a", "collection:b"), null, null, "c"))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> m.filter(new Metadata.FilterInput(List.of("collection:a"), null, null, ""))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> m.filter(new Metadata.FilterInput(List.of("collection:a"), List.of(new Metadata.Where("a", "like", 1)), null, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> m.filter(new Metadata.FilterInput(List.of("collection:a"), List.of(new Metadata.Where("", "eq", 1)), null, null))).getCode());
        assertEquals(0, calls.size());
    }

    @Test
    void everyOperatorIsAccepted() {
        for (String op : List.of("eq", "neq", "gt", "gte", "lt", "lte", "contains", "startsWith", "endsWith", "in", "notIn", "and", "or", "not")) {
            metadata().filter(new Metadata.FilterInput(List.of("collection:posts"), List.of(new Metadata.Where("a", op, 1)), null, null));
        }
        assertEquals(14, calls.size());
    }

    @Test
    void requiresProjectSlug() {
        var m = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build()).metadata();
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> m.filter(Metadata.FilterInput.of("collection:a"))).getCode());
        assertEquals(0, calls.size());
    }

    @Test
    void setStripsPrefixAndReturnsTheRecord() throws Exception {
        answer = "{\"data\":{\"id\":\"p2\",\"title\":\"Team offsite 2027\",\"priority\":9,\"secret\":\"s2\"},\"meta\":{\"requestId\":\"r1\"}}";
        var values = new LinkedHashMap<String, Object>();
        values.put("title", "Team offsite 2027");
        values.put("metadata.priority", 9);
        var out = metadata().set("posts", "p 2", values);
        var c = only();
        assertEquals("PATCH", c.method());
        assertEquals("/@portal/metadata/collection/posts/p%202", c.path());
        var b = body(c);
        assertEquals(1, b.size());
        assertEquals("Team offsite 2027", b.get("metadata").get("title").asText());
        assertEquals(9, b.get("metadata").get("priority").asInt());
        assertFalse(b.get("metadata").has("metadata.priority"));
        assertEquals("p2", out.data().get("id"));
        assertEquals("s2", out.data().get("secret"));
    }

    @Test
    void setValidationSendsNothing() {
        var m = metadata();
        assertThrows(KError.class, () -> m.set("bad name", "p1", Map.of("a", 1)));
        assertThrows(KError.class, () -> m.set(null, "p1", Map.of("a", 1)));
        assertThrows(KError.class, () -> m.set("posts", "", Map.of("a", 1)));
        assertThrows(KError.class, () -> m.set("posts", "p1", Map.of()));
        assertThrows(KError.class, () -> m.set("posts", "p1", null));
        assertEquals(0, calls.size());
    }

    @Test
    void searchPrefixesFieldAndSendsBody() throws Exception {
        answer = ROWS;
        var out = metadata().search(new Metadata.SearchInput("collection:posts", "body", "finance", 10));
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/metadata/search", c.path());
        var b = body(c);
        assertEquals("collection:posts", b.get("surface").asText());
        assertEquals("metadata.body", b.get("field").asText());
        assertEquals("finance", b.get("q").asText());
        assertEquals(10, b.get("limit").asInt());
        assertFalse(b.has("cursor"));
        assertEquals("c2", out.meta().cursor().next());
        assertEquals("p1", out.data().get(0).recordId());
    }

    @Test
    void searchKeepsAnExistingPrefixAndOmitsLimit() throws Exception {
        metadata().search(new Metadata.SearchInput("collection:posts", "metadata.body", "x", null));
        var b = body(only());
        assertEquals("metadata.body", b.get("field").asText());
        assertFalse(b.has("limit"));
    }

    @Test
    void searchValidationSendsNothing() {
        var m = metadata();
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("telemetry:x", "body", "q", null)));
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("storage:b", "body", "q", null)));
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("nope", "body", "q", null))).getCode());
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("collection:posts", null, "q", null)));
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("collection:posts", "metadata.", "q", null)));
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("collection:posts", "body", "", null)));
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("collection:posts", "body", "q", 0)));
        assertThrows(KError.class, () -> m.search(new Metadata.SearchInput("collection:posts", "body", "q", 1001)));
        assertEquals(0, calls.size());
    }

    @Test
    void nearestSendsBodyWithPrefixAndWhere() throws Exception {
        answer = "{\"data\":[{\"surface\":\"collection:posts\",\"scope\":\"posts\",\"recordId\":\"p1\",\"metadata\":{\"title\":\"Quarterly report\",\"embedding\":\"[1,0,0]\"}}],"
            + "\"meta\":{\"cursor\":{\"next\":null}}}";
        var input = new Metadata.NearestInput("collection:posts", "embedding", List.of(1.0, 0, 0), 5, "euclidean",
            List.of(new Metadata.Where("priority", "gt", 1)));
        var out = metadata().vectors().nearest(input);
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/metadata/vectors/nearest", c.path());
        var b = body(c);
        assertEquals("collection:posts", b.get("surface").asText());
        assertEquals("metadata.embedding", b.get("column").asText());
        assertEquals(3, b.get("embedding").size());
        assertEquals(1.0, b.get("embedding").get(0).asDouble());
        assertEquals(5, b.get("k").asInt());
        assertEquals("euclidean", b.get("metric").asText());
        assertEquals("metadata.priority", b.get("where").get(0).get("field").asText());
        assertEquals("p1", out.data().get(0).recordId());
        assertNull(out.meta().cursor().next());
    }

    @Test
    void nearestOmitsOptionalFields() throws Exception {
        metadata().vectors().nearest(Metadata.NearestInput.of("collection:posts", "metadata.embedding", List.of(0.5)));
        var b = body(only());
        assertEquals("metadata.embedding", b.get("column").asText());
        assertFalse(b.has("k"));
        assertFalse(b.has("metric"));
        assertFalse(b.has("where"));
    }

    @Test
    void nearestValidationSendsNothing() {
        var v = metadata().vectors();
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("telemetry:x", "e", List.of(1.0))));
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("collection:p", "", List.of(1.0))));
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("collection:p", "e", List.of())));
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("collection:p", "e", null)));
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("collection:p", "e", List.of(1.0, Double.NaN))));
        assertThrows(KError.class, () -> v.nearest(Metadata.NearestInput.of("collection:p", "e", List.of(Double.POSITIVE_INFINITY))));
        assertThrows(KError.class, () -> v.nearest(new Metadata.NearestInput("collection:p", "e", List.of(1.0), 0, null, null)));
        assertThrows(KError.class, () -> v.nearest(new Metadata.NearestInput("collection:p", "e", List.of(1.0), 1001, null, null)));
        assertThrows(KError.class, () -> v.nearest(new Metadata.NearestInput("collection:p", "e", List.of(1.0), null, "manhattan", null)));
        assertThrows(KError.class, () -> v.nearest(new Metadata.NearestInput("collection:p", "e", List.of(1.0), null, null,
            List.of(new Metadata.Where("a", "like", 1)))));
        assertEquals(0, calls.size());
    }

    @Test
    void indexGetDecodesDeclaration() {
        answer = "{\"data\":{\"collection\":\"posts\",\"columns\":[{\"column\":\"body\",\"indexed\":false},{\"column\":\"tags\",\"indexed\":true}]},\"meta\":{}}";
        var d = metadata().index().get("posts").data();
        var c = only();
        assertEquals("GET", c.method());
        assertEquals("/@portal/metadata/index/posts", c.path());
        assertTrue(c.body().isEmpty());
        assertEquals("posts", d.collection());
        assertEquals(2, d.columns().size());
        assertEquals("tags", d.columns().get(1).column());
        assertTrue(d.columns().get(1).indexed());
        assertFalse(d.columns().get(0).indexed());
    }

    @Test
    void indexDeclareSendsColumnsAndOmitsNullIndexed() throws Exception {
        answer = "{\"data\":{\"collection\":\"posts\",\"columns\":[{\"column\":\"title\",\"indexed\":false},{\"column\":\"tags\",\"indexed\":true}]},\"meta\":{}}";
        var out = metadata().index().declare("posts", List.of(Metadata.Column.of("title"), new Metadata.Column("tags", true)));
        var c = only();
        assertEquals("PUT", c.method());
        assertEquals("/@portal/metadata/index/posts", c.path());
        var b = body(c);
        assertEquals(1, b.size());
        assertEquals("title", b.get("columns").get(0).get("column").asText());
        assertFalse(b.get("columns").get(0).has("indexed"));
        assertTrue(b.get("columns").get(1).get("indexed").asBoolean());
        assertEquals(2, out.data().columns().size());
    }

    @Test
    void indexDeclareEmptyListClears() throws Exception {
        answer = "{\"data\":{\"collection\":\"posts\",\"columns\":[]},\"meta\":{}}";
        metadata().index().declare("posts", List.of());
        assertEquals(0, body(only()).get("columns").size());
    }

    @Test
    void indexValidationSendsNothing() {
        answer = "{\"data\":{\"collection\":\"posts\",\"columns\":[]},\"meta\":{}}";
        var i = metadata().index();
        var many = new ArrayList<Metadata.Column>();
        for (int n = 0; n < 65; n++) many.add(Metadata.Column.of("c" + n));
        assertThrows(KError.class, () -> i.declare("posts", many));
        assertThrows(KError.class, () -> i.declare("posts", List.of(Metadata.Column.of("id"))));
        assertThrows(KError.class, () -> i.declare("posts", List.of(Metadata.Column.of("bad name"))));
        assertThrows(KError.class, () -> i.declare("posts", List.of(Metadata.Column.of("a"), Metadata.Column.of("a"))));
        assertThrows(KError.class, () -> i.declare("posts", null));
        assertThrows(KError.class, () -> i.declare("1posts", List.of()));
        assertThrows(KError.class, () -> i.get("bad/name"));
        var sixtyFour = new ArrayList<Metadata.Column>();
        for (int n = 0; n < 64; n++) sixtyFour.add(Metadata.Column.of("c" + n));
        i.declare("posts", sixtyFour);
        assertEquals(1, calls.size());
    }

    @Test
    void decodesGatewayErrors() {
        status = 400;
        answer = "{\"code\":\"K9O_METADATA_FIELD_NOT_INDEXED\",\"message\":\"not declared\",\"context\":{\"surface\":\"collection:posts\",\"field\":\"metadata.nope\"},"
            + "\"recommendation\":{\"declare\":\"PUT index\"}}";
        var e = assertThrows(KError.class, () -> metadata().filter(new Metadata.FilterInput(List.of("collection:posts"), List.of(new Metadata.Where("nope", "eq", 1)), null, null)));
        assertEquals("K9O_METADATA_FIELD_NOT_INDEXED", e.getCode());
        assertEquals(400, e.getStatus());
        assertEquals("metadata.nope", e.getContext().get("field"));

        status = 413;
        answer = "{\"code\":\"K9O_QUERY_RESULT_TOO_LARGE\",\"message\":\"too many\",\"context\":{\"cap\":10000,\"surfaces\":[\"collection:a\"]}}";
        e = assertThrows(KError.class, () -> metadata().filter(Metadata.FilterInput.of("collection:a")));
        assertEquals("K9O_QUERY_RESULT_TOO_LARGE", e.getCode());
        assertEquals(413, e.getStatus());

        status = 501;
        answer = "{\"code\":\"K9O_NOT_IMPLEMENTED\",\"message\":\"storage\",\"context\":{\"surface\":\"storage:uploads\"}}";
        e = assertThrows(KError.class, () -> metadata().filter(Metadata.FilterInput.of("storage:uploads")));
        assertEquals("K9O_NOT_IMPLEMENTED", e.getCode());
        assertEquals(501, e.getStatus());

        status = 400;
        answer = "{\"code\":\"K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED\",\"message\":\"enc\",\"context\":{\"column\":\"secret\",\"op\":\"textSearch\"}}";
        e = assertThrows(KError.class, () -> metadata().search(new Metadata.SearchInput("collection:posts", "secret", "x", null)));
        assertEquals("K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED", e.getCode());

        status = 403;
        answer = "{\"code\":\"K9O_EXTENSION_PERMISSION_DENIED\",\"message\":\"no grant\",\"context\":{\"reason\":\"grant_absent\"}}";
        e = assertThrows(KError.class, () -> metadata().set("posts", "p1", Map.of("a", 1)));
        assertEquals("K9O_EXTENSION_PERMISSION_DENIED", e.getCode());
        assertEquals(403, e.getStatus());

        status = 400;
        answer = "{\"code\":\"K9O_UNKNOWN_SURFACE\",\"message\":\"surface\",\"context\":{\"surface\":\"collection:zzz\"}}";
        e = assertThrows(KError.class, () -> metadata().vectors().nearest(Metadata.NearestInput.of("collection:zzz", "e", List.of(1.0))));
        assertEquals("K9O_UNKNOWN_SURFACE", e.getCode());
    }

    // ---- subscribe ----

    static final String HEAD = "retry: 10\n:ok\n\n";

    static String frame(String id, String rec) {
        return "id: " + id + "\nevent: row_updated\ndata: {\"topic\":\"collections.posts.row_updated\",\"eventType\":\"row_updated\","
            + "\"payload\":{\"collection\":\"posts\",\"id\":\"" + rec + "\"},\"occurredAt\":\"2026-10-27T10:20:38.969Z\"}\n\n";
    }

    List<Realtime.RealtimeEvent> take(EventStream<Realtime.RealtimeEvent> s, int n) {
        List<Realtime.RealtimeEvent> out = new ArrayList<>();
        for (var ev : s) {
            out.add(ev);
            if (out.size() == n) break;
        }
        return out;
    }

    @Test
    void subscribeSendsSurfaceAndFromSequenceOnFirstConnectionOnly() {
        feedScript.add(HEAD + frame("c1", "p1") + frame("c2", "p2"));
        feedScript.add(HEAD + frame("c2", "p2") + frame("c3", "p3"));
        var opts = new Metadata.SubscribeOptions().fromSequence(7).reconnectDelay(Duration.ofMillis(10));
        try (var s = metadata().subscribe("collection:posts", opts)) {
            var evs = take(s, 3);
            assertEquals("row_updated", evs.get(0).eventType());
            @SuppressWarnings("unchecked") var payload = (Map<String, Object>) evs.get(0).payload();
            assertEquals("posts", payload.get("collection"));
            assertEquals("p1", payload.get("id"));
            assertEquals(List.of("c1", "c2", "c3"), evs.stream().map(Realtime.RealtimeEvent::eventId).toList());
        }
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/metadata/subscribe", calls.get(0).path());
        var first = List.of(calls.get(0).query().split("&"));
        assertEquals(2, first.size());
        assertTrue(first.contains("surface=collection%3Aposts"));
        assertTrue(first.contains("fromSequence=7"));
        assertNull(calls.get(0).lastEventId());
        assertEquals("surface=collection%3Aposts", calls.get(1).query());
        assertEquals("c2", calls.get(1).lastEventId());
    }

    @Test
    void subscribeWithoutOptionsSendsOnlySurface() {
        feedScript.add(HEAD + frame("c1", "p1"));
        try (var s = metadata().subscribe("collection:posts")) {
            assertEquals(1, take(s, 1).size());
        }
        assertEquals("surface=collection%3Aposts", calls.get(0).query());
    }

    @Test
    void subscribeResyncCallsHandlerAndDoesNotResendFromSequence() {
        feedScript.add(HEAD + frame("c1", "p1") + "event: resync\ndata: {\"reason\":\"cursor-evicted\",\"action\":\"reread-source\"}\n\n");
        feedScript.add(HEAD + frame("c9", "p9"));
        List<Realtime.Resync> resyncs = new ArrayList<>();
        var opts = new Metadata.SubscribeOptions().fromSequence(3).reconnectDelay(Duration.ofMillis(10)).onResync(resyncs::add);
        try (var s = metadata().subscribe("collection:posts", opts)) {
            assertEquals(2, take(s, 2).size());
        }
        assertEquals(1, resyncs.size());
        assertEquals("cursor-evicted", resyncs.get(0).reason());
        assertNull(calls.get(1).lastEventId());
        assertEquals("surface=collection%3Aposts", calls.get(1).query());
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void subscribeOpeningResyncIsInformationalAndEventsFollowOnTheSameConnection() {
        feedScript.add(HEAD + "event: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":7,\"action\":\"reread-source\"}\n\n" + frame("c1", "p1"));
        List<Realtime.Resync> resyncs = new ArrayList<>();
        var opts = new Metadata.SubscribeOptions().reconnectDelay(Duration.ofMillis(10)).onResync(resyncs::add);
        try (var s = metadata().subscribe("collection:posts", opts)) {
            assertEquals(1, take(s, 1).size());
        }
        assertTrue(resyncs.isEmpty());
        assertEquals(1, calls.size(), "no reconnect");
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void subscribeOpeningResyncOnAConnectionSentWithFromSequenceIsARealResync() {
        feedScript.add(HEAD + "event: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":7,\"action\":\"reread-source\"}\n\n");
        feedScript.add(HEAD + frame("c9", "p9"));
        List<Realtime.Resync> resyncs = new ArrayList<>();
        var opts = new Metadata.SubscribeOptions().fromSequence(3).reconnectDelay(Duration.ofMillis(10)).onResync(resyncs::add);
        try (var s = metadata().subscribe("collection:posts", opts)) {
            assertEquals(1, take(s, 1).size());
        }
        assertEquals(1, resyncs.size());
        assertTrue(calls.get(0).query().contains("fromSequence=3"));
        assertNull(calls.get(1).lastEventId());
        assertEquals("surface=collection%3Aposts", calls.get(1).query());
    }

    @Test
    void subscribeValidationSendsNothing() {
        var m = metadata();
        assertThrows(KError.class, () -> m.subscribe("telemetry:x"));
        assertThrows(KError.class, () -> m.subscribe("storage:b"));
        assertEquals("K9O_UNKNOWN_SURFACE", assertThrows(KError.class, () -> m.subscribe("posts")).getCode());
        assertThrows(KError.class, () -> m.subscribe("collection:posts", new Metadata.SubscribeOptions().fromSequence(-1)));
        assertEquals(0, calls.size());
    }

    @Test
    void subscribeThrowsAClientErrorOnce() {
        // a 4xx on the first connection is thrown, not retried
        server.removeContext("/");
        server.createContext("/", ex -> {
            calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), "", null));
            byte[] b = "{\"code\":\"K9O_ACCESS_DENIED\",\"message\":\"no\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(403, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        try (var s = metadata().subscribe("collection:posts")) {
            var e = assertThrows(KError.class, () -> s.iterator().hasNext());
            assertEquals("K9O_ACCESS_DENIED", e.getCode());
        }
        assertEquals(1, calls.size());
    }
}
