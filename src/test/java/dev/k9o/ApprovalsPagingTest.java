package dev.k9o;

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
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** approvals().list paging and the bulk {@code failed} rows: exact method, path, query and body, with live-shaped answers. */
class ApprovalsPagingTest {

    static final ObjectMapper M = new ObjectMapper();

    record Call(String method, String target, String body) {}

    HttpServer server;
    String baseUrl;
    final List<Call> calls = new CopyOnWriteArrayList<>();
    volatile Function<Call, String[]> handler = c -> new String[] {"200", "{\"data\":null,\"meta\":{}}"};

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/", ex -> {
            Call call = new Call(ex.getRequestMethod(), ex.getRequestURI().toString(),
                new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            calls.add(call);
            String[] r = handler.apply(call);
            byte[] b = r[1].getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(Integer.parseInt(r[0]), b.length);
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

    Call last() { return calls.get(calls.size() - 1); }

    static String row(String id) {
        return "{\"id\":\"" + id + "\",\"status\":\"rejected\",\"collection\":\"conformance_kv\",\"operation\":\"insert\","
            + "\"makerId\":\"usr_conformance0001\",\"proposedPayload\":{\"id\":\"w2-probe-php-22b5761e\",\"val\":\"php-probe\"},"
            + "\"targetRecordId\":null,\"approverId\":\"usr_conformance0001\",\"decidedAt\":\"2026-10-26T18:25:20.573Z\","
            + "\"decisionReason\":\"php sdk probe, not intended\",\"createdAt\":\"2026-10-26T18:25:19.818Z\",\"expiresAt\":null}";
    }

    static String page(String id, String next) {
        return "{\"data\":[" + row(id) + "],\"meta\":{\"cost\":{},\"timing\":{},\"context\":{\"requestId\":\"req_1\","
            + "\"traceId\":\"req_1\",\"projectId\":\"proj_1\"},\"cursor\":{\"next\":" + next + "}}}";
    }

    static final String CURSOR = "eyJ2IjoxLCJwIjoicHJval8xIiwicyI6bnVsbCwicSI6InF1ZXVlIiwidCI6IjIwMjYtMTAtMjZUMTg6MjU6MTkuODE4MDAwWiIsImkiOiJhcHJfN0ZqTXZ6bkFhVmpsIn0";

    static final Map<String, Object> CRITERIA = Map.of("eq", List.of("change.table", "__no_such_collection_probe__"));

    @Test
    void firstPageThenLastPage() {
        handler = c -> c.target().contains("cursor=")
            ? new String[] {"200", page("apr_2", "null")}
            : new String[] {"200", page("apr_1", "\"" + CURSOR + "\"")};

        var first = client().approvals().list(null, 1);
        assertEquals("GET", last().method());
        assertEquals("/@portal/pending-approvals?limit=1", last().target());
        assertEquals("", last().body());
        assertEquals(1, first.data().size());
        assertEquals("apr_1", first.data().get(0).id());
        assertEquals("rejected", first.data().get(0).status());
        assertEquals(CURSOR, first.meta().cursor().next());

        var second = client().approvals().list(null, 1, first.meta().cursor().next());
        assertEquals("GET", last().method());
        assertEquals("/@portal/pending-approvals?limit=1&cursor=" + CURSOR, last().target());
        assertEquals("apr_2", second.data().get(0).id());
        assertNull(second.meta().cursor().next());
    }

    @Test
    void statusLimitAndCursorTogether() {
        handler = c -> new String[] {"200", page("apr_1", "null")};
        client().approvals().list("approved", 50, CURSOR);
        assertEquals("/@portal/pending-approvals?status=approved&limit=50&cursor=" + CURSOR, last().target());
    }

    @Test
    void existingCallsSendNoCursor() {
        handler = c -> new String[] {"200", page("apr_1", "null")};
        client().approvals().list();
        assertEquals("/@portal/pending-approvals", last().target());
        client().approvals().list("pending_approval", 20);
        assertEquals("/@portal/pending-approvals?status=pending_approval&limit=20", last().target());
    }

    @Test
    void invalidCursorIs400() {
        handler = c -> new String[] {"400", "{\"code\":\"K9O_INVALID_CURSOR\",\"message\":\"Cursor is malformed; omit it to start from the newest pending change\","
            + "\"context\":{},\"recommendation\":null}"};
        KError e = assertThrows(KError.class, () -> client().approvals().list(null, null, "abc!"));
        assertEquals("K9O_INVALID_CURSOR", e.getCode());
        assertEquals(400, e.getStatus());
        assertEquals("/@portal/pending-approvals?cursor=abc%21", last().target());
    }

    @Test
    void bulkWithAFailedRow() throws IOException {
        handler = c -> new String[] {"200", "{\"data\":{\"attestationId\":\"apr_Vf23zMoj7m0u\",\"action\":\"approve\",\"affectedCount\":1,"
            + "\"affectedIds\":[\"apr_A\"],\"failed\":[{\"id\":\"apr_B\",\"code\":\"K9O_STATE_CONFLICT\","
            + "\"reason\":\"Pending change 'apr_B' was decided concurrently\"}],\"reason\":null},\"meta\":{}}"};
        var run = client().approvals().bulk("approve", Map.of("eq", List.of("change.table", "orders")), null).data();
        assertEquals("POST", last().method());
        assertEquals("/@portal/pending-approvals/bulk", last().target());
        assertEquals(M.readTree("{\"action\":\"approve\",\"criteria\":{\"eq\":[\"change.table\",\"orders\"]}}"), M.readTree(last().body()));
        assertEquals(1, run.affectedCount());
        assertEquals(List.of("apr_A"), run.affectedIds());
        assertEquals(1, run.failed().size());
        assertEquals("apr_B", run.failed().get(0).id());
        assertEquals("K9O_STATE_CONFLICT", run.failed().get(0).code());
        assertEquals("Pending change 'apr_B' was decided concurrently", run.failed().get(0).reason());
        assertNull(run.reason());
    }

    @Test
    void bulkWithEmptyFailed() throws IOException {
        handler = c -> new String[] {"200", "{\"data\":{\"attestationId\":\"apr_Vf23zMoj7m0u\",\"action\":\"reject\",\"affectedCount\":0,"
            + "\"affectedIds\":[],\"failed\":[],\"reason\":\"probe: matches nothing\"},\"meta\":{}}"};
        var run = client().approvals().bulk("reject", CRITERIA, "probe: matches nothing").data();
        assertEquals(M.readTree("{\"action\":\"reject\",\"criteria\":{\"eq\":[\"change.table\",\"__no_such_collection_probe__\"]},"
            + "\"reason\":\"probe: matches nothing\"}"), M.readTree(last().body()));
        assertEquals(0, run.affectedCount());
        assertTrue(run.failed().isEmpty());
        assertEquals("probe: matches nothing", run.reason());
    }

    @Test
    void bulkWithoutFailedDecodesAsEmpty() {
        handler = c -> new String[] {"200", "{\"data\":{\"attestationId\":\"apr_1\",\"action\":\"approve\",\"affectedCount\":0,"
            + "\"affectedIds\":[],\"reason\":null},\"meta\":{}}"};
        var run = client().approvals().bulk("approve", CRITERIA, null).data();
        assertNotNull(run.failed());
        assertTrue(run.failed().isEmpty());
    }
}
