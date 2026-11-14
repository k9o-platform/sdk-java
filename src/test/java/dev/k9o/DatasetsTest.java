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
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** analytics() and rollups(): method, path, query, body and answer decoding of every route; errors; the rollup feed. */
class DatasetsTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record Call(String method, String path, String query, byte[] body, String contentType, String accept, String lastEventId) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
    }

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile int status = 200;
    volatile String answer = "{\"data\":{},\"meta\":{}}";
    volatile String answerType = "application/json";
    volatile byte[] answerBytes;
    volatile String retryAfter;
    final List<String> feedScript = new CopyOnWriteArrayList<>();
    final AtomicInteger feedHits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            byte[] body = ex.getRequestBody().readAllBytes();
            calls.add(new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), body,
                ex.getRequestHeaders().getFirst("Content-Type"), ex.getRequestHeaders().getFirst("Accept"),
                ex.getRequestHeaders().getFirst("Last-Event-ID")));
            if (ex.getRequestURI().getPath().endsWith("/subscribe")) {
                int n = feedHits.getAndIncrement();
                String b = feedScript.get(Math.min(n, feedScript.size() - 1));
                ex.getResponseHeaders().add("Content-Type", "text/event-stream");
                ex.sendResponseHeaders(200, 0);
                try (OutputStream os = ex.getResponseBody()) { os.write(b.getBytes(StandardCharsets.UTF_8)); }
                return;
            }
            byte[] b = answerBytes != null ? answerBytes : answer.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", answerType);
            if (retryAfter != null) ex.getResponseHeaders().add("Retry-After", retryAfter);
            ex.sendResponseHeaders(status, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() { if (server != null) server.stop(0); }

    K9o.K9oClient client() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").build());
    }

    Call only() {
        assertEquals(1, calls.size());
        return calls.get(0);
    }

    JsonNode body(Call c) throws IOException { return JSON.readTree(c.body()); }

    // ---- datasets

    @Test
    void listDecodesTheDatasets() {
        answer = "{\"data\":[{\"slug\":\"orders\",\"displayName\":\"Orders\"},{\"slug\":\"visits\"}],\"meta\":{}}";
        var out = client().analytics().list();
        var c = only();
        assertEquals("GET", c.method());
        assertEquals("/@portal/analytics", c.path());
        assertNull(c.query());
        assertEquals(2, out.data().size());
        assertEquals("orders", out.data().get(0).get("slug"));
    }

    @Test
    void listReadsItemsFormToo() {
        answer = "{\"data\":{\"items\":[{\"slug\":\"orders\"}]},\"meta\":{}}";
        assertEquals("orders", client().analytics().list().data().get(0).get("slug"));
    }

    @Test
    void listReadsTheKeyedFormsOfTheLiveGateway() {
        answer = "{\"data\":{\"rollups\":[{\"slug\":\"hourly\"}]},\"meta\":{}}";
        assertEquals("hourly", client().rollups().list().data().get(0).get("slug"));
        answer = "{\"data\":{\"schedules\":[{\"id\":\"sc_1\"}]},\"meta\":{}}";
        assertEquals("sc_1", client().analytics().schedules().list("visits").data().get(0).get("id"));
        answer = "{\"data\":{\"a\":[],\"b\":[]},\"meta\":{}}";
        assertEquals("K9O_INTERNAL_ERROR", assertThrows(KError.class, () -> client().rollups().list()).getCode());
    }

    @Test
    void listRefusesAnAnswerThatIsNotAList() {
        answer = "{\"data\":{\"slug\":\"orders\"},\"meta\":{}}";
        assertEquals("K9O_INTERNAL_ERROR", assertThrows(KError.class, () -> client().analytics().list()).getCode());
    }

    @Test
    void createSendsTheBody() throws Exception {
        answer = "{\"data\":{\"slug\":\"orders\",\"endpoints\":{}},\"meta\":{}}";
        var in = new Analytics.CreateInput("Orders", List.of(new Analytics.Column("id", "string"),
            new Analytics.Column("created_at", "timestamp")), List.of("created_at:day"), "shared", Analytics.StorageTarget.byos("bind_1"));
        var out = client().analytics().create("orders", in);
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/orders", c.path());
        var b = body(c);
        assertEquals("Orders", b.get("displayName").asText());
        assertEquals("created_at", b.get("columns").get(1).get("name").asText());
        assertEquals("timestamp", b.get("columns").get(1).get("type").asText());
        assertEquals("created_at:day", b.get("partitionBy").get(0).asText());
        assertEquals("shared", b.get("residencyClass").asText());
        assertEquals("byos", b.get("storageTarget").get("kind").asText());
        assertEquals("bind_1", b.get("storageTarget").get("bindingId").asText());
        assertEquals("orders", out.data().get("slug"));
    }

    @Test
    void createManagedTargetAndChecks() throws Exception {
        var cols = List.of(new Analytics.Column("id", "string"));
        client().analytics().create("orders", new Analytics.CreateInput("Orders", cols, null, "per-subject", Analytics.StorageTarget.managed()));
        var b = body(only());
        assertEquals("managed", b.get("storageTarget").get("kind").asText());
        assertFalse(b.has("partitionBy"));
        assertFalse(b.get("storageTarget").has("bindingId"));

        calls.clear();
        var a = client().analytics();
        for (var bad : List.<Analytics.CreateInput>of(
            new Analytics.CreateInput("", cols, "shared"),
            new Analytics.CreateInput("X", List.of(), "shared"),
            new Analytics.CreateInput("X", cols, "everyone"),
            new Analytics.CreateInput("X", cols, null, "shared", new Analytics.StorageTarget("byos", null)),
            new Analytics.CreateInput("X", cols, null, "shared", new Analytics.StorageTarget("cloud", null)))) {
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.create("orders", bad)).getCode());
        }
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.create("", cols == null ? null : new Analytics.CreateInput("X", cols, "shared"))).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void getAndDelete() {
        answer = "{\"data\":{\"slug\":\"orders\"},\"meta\":{}}";
        var out = client().analytics().get("orders");
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/analytics/orders", calls.get(0).path());
        assertEquals("orders", out.data().get("slug"));
        answer = "{\"data\":{\"slug\":\"orders\",\"status\":\"retired\"},\"meta\":{}}";
        var del = client().analytics().delete("orders");
        assertEquals("DELETE", calls.get(1).method());
        assertEquals("/@portal/analytics/orders", calls.get(1).path());
        assertEquals("retired", del.data().get("status"));
    }

    @Test
    void writeSendsRowsAndOnError() throws Exception {
        answer = "{\"data\":{\"ingested\":2,\"skipped\":0,\"skippedDetails\":[],\"partitions\":[\"region=de01\"],\"bytes\":120},\"meta\":{}}";
        var rows = List.of(Map.<String, Object>of("id", "o1", "total", 10), Map.<String, Object>of("id", "o2", "total", 20));
        var out = client().analytics().write("orders", rows, Analytics.OnError.REJECT);
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/orders/write", c.path());
        assertEquals("onError=reject", c.query());
        var b = body(c);
        assertTrue(b.isArray());
        assertEquals("o2", b.get(1).get("id").asText());
        assertEquals(2, out.data().get("ingested"));
        calls.clear();
        client().analytics().write("orders", rows);
        assertNull(only().query());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().write("orders", List.of())).getCode());
    }

    @Test
    void writeErrorsAreTyped() {
        status = 400;
        answer = "{\"code\":\"K9O_INGEST_SCHEMA_MISMATCH\",\"message\":\"bad row\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> client().analytics().write("orders", List.of(Map.of("id", 1)), Analytics.OnError.REJECT));
        assertEquals("K9O_INGEST_SCHEMA_MISMATCH", e.getCode());
        assertEquals(400, e.getStatus());
        calls.clear();
        status = 503;
        answer = "{\"code\":\"K9O_ANALYTICS_UNAVAILABLE\",\"message\":\"not yet\",\"context\":{}}";
        assertEquals("K9O_ANALYTICS_UNAVAILABLE", assertThrows(KError.class, () -> client().analytics().query("select 1", List.of("orders"))).getCode());
    }

    @Test
    void materializeSendsExactlyOneSource() throws Exception {
        answer = "{\"data\":{\"rowsRead\":5,\"ingested\":5,\"rejected\":0,\"partitions\":[]},\"meta\":{}}";
        var out = client().analytics().materialize("orders", Analytics.MaterializeInput.fromCollection("orders_live"));
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/orders/materialize", c.path());
        var b = body(c);
        assertEquals("orders_live", b.get("fromCollection").asText());
        assertFalse(b.has("fromQuery"));
        assertEquals(5, out.data().get("rowsRead"));
        calls.clear();
        client().analytics().materialize("orders", Analytics.MaterializeInput.fromQuery("q_1"));
        assertEquals("q_1", body(only()).get("fromQuery").asText());
        calls.clear();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> client().analytics().materialize("orders", new Analytics.MaterializeInput("a", "b"))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> client().analytics().materialize("orders", new Analytics.MaterializeInput(null, null))).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void uploadSendsTheFileRawWithQuery() {
        answer = "{\"data\":{\"ingested\":3,\"skipped\":0},\"meta\":{}}";
        byte[] csv = "id,total\no1,10\n".getBytes(StandardCharsets.UTF_8);
        var opts = new Analytics.UploadOptions(Analytics.Format.CSV, true, Analytics.OnError.SKIP, ";", "utf-8", false, null, null);
        var out = client().analytics().upload("orders", csv, opts);
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/orders/upload", c.path());
        assertEquals("format=csv&dryRun=true&onError=skip&delimiter=%3B&encoding=utf-8&hasHeader=false", c.query());
        assertEquals("text/csv", c.contentType());
        assertArrayEquals(csv, c.body());
        assertEquals(3, out.data().get("ingested"));
    }

    @Test
    void uploadWithManifestIsMultipart() throws Exception {
        byte[] json = "[{\"id\":\"o1\"}]".getBytes(StandardCharsets.UTF_8);
        var manifest = Map.<String, Object>of("displayName", "Orders", "residencyClass", "shared",
            "columns", List.of(Map.of("name", "id", "type", "string")));
        client().analytics().upload("orders", json, new Analytics.UploadOptions(Analytics.Format.JSON, null, null, null, null, null, manifest, "orders.json"));
        var c = only();
        assertEquals("format=json", c.query());
        assertTrue(c.contentType().startsWith("multipart/form-data; boundary="));
        String text = c.text();
        assertTrue(text.contains("name=\"file\"; filename=\"orders.json\""));
        assertTrue(text.contains("[{\"id\":\"o1\"}]"));
        assertTrue(text.contains("name=\"manifest\""));
        assertTrue(text.contains("\"displayName\":\"Orders\""));
    }

    @Test
    void uploadChecksArguments() {
        var a = client().analytics();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.upload("orders", new byte[0], Analytics.UploadOptions.of(Analytics.Format.CSV))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.upload("orders", new byte[]{1}, null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.upload("orders", new byte[]{1}, new Analytics.UploadOptions(null, null, null, null, null, null, null, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.upload("orders", new byte[25 * 1024 * 1024 + 1], Analytics.UploadOptions.of(Analytics.Format.CSV))).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void downloadOriginalReturnsBytesAndType() {
        answerBytes = "id,total\r\no1,10\r\n".getBytes(StandardCharsets.UTF_8);
        answerType = "text/csv";
        var f = client().analytics().downloadOriginal("orders");
        var c = only();
        assertEquals("GET", c.method());
        assertEquals("/@portal/analytics/orders/upload/original", c.path());
        assertEquals("*/*", c.accept());
        assertArrayEquals(answerBytes, f.bytes());
        assertEquals("text/csv", f.contentType());
    }

    @Test
    void downloadOriginalErrorIsTyped() {
        status = 404;
        answer = "{\"code\":\"K9O_NOT_FOUND\",\"message\":\"no upload\",\"context\":{}}";
        var e = assertThrows(KError.class, () -> client().analytics().downloadOriginal("orders"));
        assertEquals("K9O_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
    }

    @Test
    void querySendsSqlAndDatasets() throws Exception {
        answer = "{\"data\":{\"rows\":[{\"n\":3}]},\"meta\":{}}";
        var out = client().analytics().query("select count(*) as n from dataset.orders", List.of("orders"));
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/query", c.path());
        var b = body(c);
        assertEquals("select count(*) as n from dataset.orders", b.get("sql").asText());
        assertEquals("orders", b.get("datasets").get(0).asText());
        assertTrue(out.data() instanceof Map<?, ?>);
        calls.clear();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().query("", List.of("orders"))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().query("select 1", List.of())).getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void readPagesWithACursor() throws Exception {
        answer = "{\"data\":[{\"_k9o_row_id\":\"r1\",\"id\":\"o1\"},{\"_k9o_row_id\":\"r2\",\"id\":\"o2\"}],\"meta\":{\"cursor\":{\"next\":\"c2\"}}}";
        var out = client().analytics().read("orders", new Analytics.ReadOptions(Map.of("region", "de01"), "c1", 2));
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/orders/read", c.path());
        var b = body(c);
        assertEquals("de01", b.get("partitionFilter").get("region").asText());
        assertEquals("c1", b.get("cursor").asText());
        assertEquals(2, b.get("limit").asInt());
        assertEquals("r2", out.data().get(1).get("_k9o_row_id"));
        assertEquals("c2", out.meta().cursor().next());
        calls.clear();
        client().analytics().read("orders", null);
        assertEquals("{}", only().text());
    }

    // ---- rollups and schedules of a dataset

    @Test
    void rollupCreateSendsTheBody() throws Exception {
        answer = "{\"data\":{\"id\":\"rl_1\",\"slug\":\"hourly\"},\"meta\":{}}";
        var in = new Analytics.DatasetRollups.CreateInput("hourly", "page_views", List.of("country"),
            List.of(Analytics.DatasetRollups.Measure.count("views"), new Analytics.DatasetRollups.Measure("revenue", "sum", "total")),
            3600, null, true, 30);
        var out = client().analytics().rollups().create("visits", in);
        var c = only();
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/visits/rollups", c.path());
        var b = body(c);
        assertEquals("hourly", b.get("slug").asText());
        assertEquals("page_views", b.get("source").asText());
        assertEquals("country", b.get("dimensions").get(0).asText());
        assertEquals("count", b.get("measures").get(0).get("agg").asText());
        assertFalse(b.get("measures").get(0).has("column"));
        assertEquals("total", b.get("measures").get(1).get("column").asText());
        assertEquals(3600, b.get("windowSeconds").asInt());
        assertTrue(b.get("nonPersonal").asBoolean());
        assertFalse(b.has("subjectField"));
        assertEquals(30, b.get("reattestIntervalDays").asInt());
        assertEquals("rl_1", out.data().get("id"));
    }

    @Test
    void rollupCreateChecks() {
        var r = client().analytics().rollups();
        var m = List.of(Analytics.DatasetRollups.Measure.count("n"));
        for (var bad : List.of(
            new Analytics.DatasetRollups.CreateInput("h", "s", null, m, 3600, null, null, null),           // neither subjectField nor nonPersonal
            new Analytics.DatasetRollups.CreateInput("h", "s", null, m, 3600, "user_id", true, null),      // both
            new Analytics.DatasetRollups.CreateInput("h", "s", null, m, 30, null, true, null),             // window under 60
            new Analytics.DatasetRollups.CreateInput("h", "s", null, List.of(), 3600, null, true, null),   // no measures
            new Analytics.DatasetRollups.CreateInput("h", "s", null, m, 3600, null, true, 14),             // bad interval
            new Analytics.DatasetRollups.CreateInput("", "s", null, m, 3600, null, true, null))) {
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> r.create("visits", bad)).getCode());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void rollupCreatePerSubject() throws Exception {
        client().analytics().rollups().create("visits", new Analytics.DatasetRollups.CreateInput("h", "s", null,
            List.of(Analytics.DatasetRollups.Measure.count("n")), 60, "user_id", null, null));
        var b = body(only());
        assertEquals("user_id", b.get("subjectField").asText());
        assertFalse(b.has("nonPersonal"));
        assertFalse(b.has("reattestIntervalDays"));
    }

    @Test
    void rollupListDeleteReattest() throws Exception {
        answer = "{\"data\":[{\"id\":\"rl_1\",\"attestationStatus\":\"expiring_soon\"}],\"meta\":{}}";
        var list = client().analytics().rollups().list("visits");
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/analytics/visits/rollups", calls.get(0).path());
        assertEquals("expiring_soon", list.data().get(0).get("attestationStatus"));

        answer = "{\"data\":{\"id\":\"rl_1\",\"deleted\":true},\"meta\":{}}";
        var del = client().analytics().rollups().delete("visits", "rl_1");
        assertEquals("DELETE", calls.get(1).method());
        assertEquals("/@portal/analytics/visits/rollups/rl_1", calls.get(1).path());
        assertEquals(true, del.data().get("deleted"));

        answer = "{\"data\":{\"id\":\"rl_1\",\"expiresAt\":\"2027-01-26T00:00:00Z\"},\"meta\":{}}";
        var re = client().analytics().rollups().reattest("visits", "rl_1", 90);
        assertEquals("POST", calls.get(2).method());
        assertEquals("/@portal/analytics/visits/rollups/rl_1/reattest", calls.get(2).path());
        assertEquals(90, body(calls.get(2)).get("reattestIntervalDays").asInt());
        assertEquals("2027-01-26T00:00:00Z", re.data().get("expiresAt"));

        client().analytics().rollups().reattest("visits", "rl_1");
        assertEquals("{}", calls.get(3).text());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().rollups().reattest("visits", "rl_1", 10)).getCode());
    }

    @Test
    void reattestIsHumanOnlyAndTheErrorIsTheGateways() {
        status = 403;
        answer = "{\"code\":\"K9O_RBAC_DENY\",\"message\":\"only a person can re-attest\",\"context\":{\"reason\":\"human_only\"}}";
        var e = assertThrows(KError.class, () -> client().analytics().rollups().reattest("visits", "rl_1"));
        assertEquals("K9O_RBAC_DENY", e.getCode());
        assertEquals("human_only", e.getContext().get("reason"));
        assertEquals(403, e.getStatus());
        var e2 = assertThrows(KError.class, () -> client().analytics().schedules().reattest("visits", "sc_1", 7));
        assertEquals("human_only", e2.getContext().get("reason"));
    }

    @Test
    void scheduleCreateListDeleteReattest() throws Exception {
        answer = "{\"data\":{\"id\":\"sc_1\"},\"meta\":{}}";
        client().analytics().schedules().create("visits", new Analytics.DatasetSchedules.CreateInput("page_views", 300, 7));
        var c = calls.get(0);
        assertEquals("POST", c.method());
        assertEquals("/@portal/analytics/visits/schedules", c.path());
        var b = body(c);
        assertEquals("page_views", b.get("fromCollection").asText());
        assertEquals(300, b.get("intervalSeconds").asInt());
        assertEquals(7, b.get("reattestIntervalDays").asInt());

        client().analytics().schedules().create("visits", new Analytics.DatasetSchedules.CreateInput("page_views", 60, null));
        assertFalse(body(calls.get(1)).has("reattestIntervalDays"));

        answer = "{\"data\":[{\"id\":\"sc_1\",\"lastRunStatus\":\"ok\",\"nextRunAt\":\"2026-10-27T10:05:00Z\",\"attestationStatus\":\"active\"}],\"meta\":{}}";
        var list = client().analytics().schedules().list("visits");
        assertEquals("GET", calls.get(2).method());
        assertEquals("/@portal/analytics/visits/schedules", calls.get(2).path());
        assertEquals("ok", list.data().get(0).get("lastRunStatus"));

        answer = "{\"data\":{\"id\":\"sc_1\",\"deleted\":true},\"meta\":{}}";
        client().analytics().schedules().delete("visits", "sc_1");
        assertEquals("DELETE", calls.get(3).method());
        assertEquals("/@portal/analytics/visits/schedules/sc_1", calls.get(3).path());

        answer = "{\"data\":{\"id\":\"sc_1\"},\"meta\":{}}";
        client().analytics().schedules().reattest("visits", "sc_1", 365);
        assertEquals("POST", calls.get(4).method());
        assertEquals("/@portal/analytics/visits/schedules/sc_1/reattest", calls.get(4).path());
        assertEquals(365, body(calls.get(4)).get("reattestIntervalDays").asInt());
    }

    @Test
    void scheduleCreateChecksAndConflict() {
        var s = client().analytics().schedules();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> s.create("visits", new Analytics.DatasetSchedules.CreateInput("c", 59, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> s.create("visits", new Analytics.DatasetSchedules.CreateInput("", 60, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> s.create("visits", new Analytics.DatasetSchedules.CreateInput("c", 60, 5))).getCode());
        assertTrue(calls.isEmpty());
        status = 409;
        answer = "{\"code\":\"K9O_PENDING_CHANGE_ALREADY_EXISTS\",\"message\":\"exists\",\"context\":{}}";
        assertEquals("K9O_PENDING_CHANGE_ALREADY_EXISTS", assertThrows(KError.class,
            () -> s.create("visits", new Analytics.DatasetSchedules.CreateInput("c", 60, null))).getCode());
    }

    @Test
    void pathSegmentsAreEncodedAndRequired() {
        client().analytics().get("a b/c");
        assertEquals("/@portal/analytics/a%20b%2Fc", only().path());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().get("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().analytics().rollups().delete("visits", null)).getCode());
        var noProject = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> noProject.analytics().list()).getCode());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> noProject.rollups().list()).getCode());
    }

    // ---- top-level rollups

    @Test
    void rollupsListReadAndQuery() {
        answer = "{\"data\":[{\"slug\":\"hourly\",\"read\":\"/rollups/hourly/read\"}],\"meta\":{}}";
        var list = client().rollups().list();
        assertEquals("GET", calls.get(0).method());
        assertEquals("/@portal/rollups", calls.get(0).path());
        assertEquals("hourly", list.data().get(0).get("slug"));

        answer = "{\"data\":{\"cells\":[{\"status\":\"suppressed\",\"reason\":\"cohort_below_k\"}],\"coverage\":{\"complete\":true}},\"meta\":{}}";
        var read = client().rollups().read("hourly", new Rollups.ReadOptions("2026-10-27T00:00:00Z", "2026-10-27T06:00:00Z", List.of("country", "device")));
        var c = calls.get(1);
        assertEquals("GET", c.method());
        assertEquals("/@portal/rollups/hourly/read", c.path());
        assertTrue(c.query().contains("from=2026-10-27T00%3A00%3A00Z"));
        assertTrue(c.query().contains("to=2026-10-27T06%3A00%3A00Z"));
        assertTrue(c.query().contains("dims=country%2Cdevice"));
        assertTrue(read.data() instanceof Map<?, ?>);
        assertEquals("cohort_below_k", ((Map<?, ?>) ((List<?>) ((Map<?, ?>) read.data()).get("cells")).get(0)).get("reason"));

        client().rollups().read("hourly");
        assertNull(calls.get(2).query());
    }

    @Test
    void rollupsReadConflictAndSubscribeLimitAreTyped() {
        status = 409;
        answer = "{\"code\":\"K9O_STATE_CONFLICT\",\"message\":\"no cohort marker\",\"context\":{}}";
        assertEquals("K9O_STATE_CONFLICT", assertThrows(KError.class, () -> client().rollups().read("hourly")).getCode());
        calls.clear();
        status = 400;
        answer = "{\"code\":\"K9O_INVALID_FIELD_VALUE\",\"message\":\"range\",\"context\":{}}";
        assertEquals(400, assertThrows(KError.class, () -> client().rollups().read("hourly")).getStatus());
    }

    /** A rollup frame as the gateway sends it: {@code id} is the resume cursor, {@code eventId} de-duplicates. */
    static String cell(String cursor, String eventId, int n) {
        return "id: " + cursor + "\nevent: cell\ndata: {\"topic\":\"rollups.hourly\",\"eventType\":\"cell\",\"payload\":{\"cellKey\":\"k" + n
            + "\",\"bucket\":\"2026-10-27T10:00:00.000Z\",\"dims\":{\"country\":\"de\"},\"final\":true,\"measures\":{\"views\":" + n
            + "}},\"occurredAt\":\"2026-10-27T10:00:05.000Z\",\"eventId\":\"" + eventId + "\"}\n\n";
    }

    static final String SUPPRESSED = "id: 90\nevent: cell\ndata: {\"topic\":\"rollups.hourly\",\"eventType\":\"cell\",\"payload\":{\"cellKey\":\"kx\","
        + "\"bucket\":\"2026-10-27T10:00:00.000Z\",\"dims\":{},\"final\":true,\"status\":\"suppressed\",\"reason\":\"cohort_below_k\"},"
        + "\"occurredAt\":\"2026-10-27T10:00:06.000Z\",\"eventId\":\"evt_s\"}\n\n";

    @Test
    void subscribeYieldsCellsResumesAndDedupes() {
        feedScript.add("retry: 10\n:ok\n\n" + cell("41", "evt_1", 1) + cell("42", "evt_2", 2));
        feedScript.add("retry: 10\n:hb\n\n" + cell("43", "evt_2", 2) + cell("44", "evt_3", 3));
        var opts = new Rollups.SubscribeOptions().reconnectDelay(Duration.ofMillis(10)).fromSequence(40);
        List<Rollups.Cell> got = new ArrayList<>();
        try (var s = client().rollups().subscribe("hourly", opts)) {
            for (var ev : s) {
                got.add(ev);
                if (got.size() == 3) break;
            }
        }
        assertEquals(List.of("evt_1", "evt_2", "evt_3"), got.stream().map(Rollups.Cell::eventId).toList());
        var c = got.get(1);
        assertEquals("k2", c.cellKey());
        assertEquals("2026-10-27T10:00:00.000Z", c.bucket());
        assertEquals(Map.of("country", "de"), c.dims());
        assertEquals(Boolean.TRUE, c.isFinal());
        assertEquals(2, c.measures().get("views"));
        assertFalse(c.suppressed());
        assertEquals("2026-10-27T10:00:05.000Z", c.occurredAt());
        assertEquals("/@portal/rollups/hourly/subscribe", calls.get(0).path());
        assertEquals("fromSequence=40", calls.get(0).query());
        assertNull(calls.get(0).lastEventId());
        assertEquals("42", calls.get(1).lastEventId(), "the SSE id is the resume cursor");
        assertNull(calls.get(1).query(), "fromSequence is for the first connection only");
    }

    @Test
    void subscribeSuppressedCellIsATypedField() {
        feedScript.add("retry: 10\n:ok\n\n" + SUPPRESSED);
        try (var s = client().rollups().subscribe("hourly", new Rollups.SubscribeOptions().reconnectDelay(Duration.ofMillis(10)))) {
            var c = s.iterator().next();
            assertTrue(c.suppressed());
            assertEquals("suppressed", c.status());
            assertEquals("cohort_below_k", c.reason());
            assertNull(c.measures());
            assertEquals("evt_s", c.eventId());
        }
    }

    @Test
    void subscribeResyncCallsTheHandler() {
        feedScript.add("retry: 10\n:ok\n\n" + cell("1", "evt_1", 1) + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"reread-source\"}\n\n");
        feedScript.add("retry: 10\n:ok\n\n" + cell("9", "evt_9", 9));
        List<String> actions = new ArrayList<>();
        var opts = new Rollups.SubscribeOptions().reconnectDelay(Duration.ofMillis(10)).onResync(r -> actions.add(r.action()));
        List<String> ids = new ArrayList<>();
        try (var s = client().rollups().subscribe("hourly", opts)) {
            for (var ev : s) {
                ids.add(ev.eventId());
                if (ids.size() == 2) break;
            }
        }
        assertEquals(List.of("evt_1", "evt_9"), ids);
        assertEquals(List.of("reread-source"), actions);
        assertNull(calls.get(1).lastEventId());
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void subscribeOpeningResyncIsInformationalAndCellsFollowOnTheSameConnection() {
        feedScript.add("retry: 10\n:ok\n\nevent: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":7,\"action\":\"reread-source\"}\n\n" + cell("1", "evt_1", 1));
        List<String> actions = new ArrayList<>();
        var opts = new Rollups.SubscribeOptions().reconnectDelay(Duration.ofMillis(10)).onResync(r -> actions.add(r.action()));
        try (var s = client().rollups().subscribe("hourly", opts)) {
            for (var ev : s) {
                assertEquals("evt_1", ev.eventId());
                break;
            }
        }
        assertTrue(actions.isEmpty());
        assertEquals(1, calls.size(), "no reconnect");
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void subscribeOpeningResyncOnAConnectionSentWithFromSequenceIsARealResync() {
        feedScript.add("retry: 10\n:ok\n\nevent: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":7,\"action\":\"reread-source\"}\n\n");
        feedScript.add("retry: 10\n:ok\n\n" + cell("9", "evt_9", 9));
        List<String> actions = new ArrayList<>();
        var opts = new Rollups.SubscribeOptions().fromSequence(3).reconnectDelay(Duration.ofMillis(10)).onResync(r -> actions.add(r.action()));
        try (var s = client().rollups().subscribe("hourly", opts)) {
            for (var ev : s) { assertEquals("evt_9", ev.eventId()); break; }
        }
        assertEquals(List.of("reread-source"), actions);
        assertEquals("fromSequence=3", calls.get(0).query());
        assertNull(calls.get(1).query());
    }

    @Test
    void subscribeChecksFromSequenceAndSlug() {
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> client().rollups().subscribe("hourly", new Rollups.SubscribeOptions().fromSequence(-1))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> client().rollups().subscribe("")).getCode());
        assertTrue(calls.isEmpty());
    }
}
