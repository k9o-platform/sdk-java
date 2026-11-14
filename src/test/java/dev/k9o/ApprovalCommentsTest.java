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
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** approvals().comments(): exact method, path, query and body, client-side checks, answer parsing and error mapping. */
class ApprovalCommentsTest {

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

    Approvals.Comments comments() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build()).approvals().comments();
    }

    Call last() { return calls.get(calls.size() - 1); }

    static final String META = "\"meta\":{\"cost\":{},\"timing\":{\"gatewayMs\":1,\"totalMs\":2},\"context\":{\"requestId\":\"req_1\",\"traceId\":\"req_1\"}}";

    static String comment(String id, String visibility, String body) {
        return "{\"id\":\"" + id + "\",\"caseId\":\"pch_demo00000001\",\"authorId\":\"usr_demo00000001\",\"visibility\":\""
            + visibility + "\",\"body\":\"" + body + "\",\"createdAt\":\"2026-11-13T09:30:00.000Z\"}";
    }

    @Test
    void addSendsPostWithBodyAndVisibilityAndParsesTheComment() throws Exception {
        handler = c -> new String[] {"201", "{\"data\":" + comment("acm_01", "approvers_only", "Check the total") + "," + META + "}"};
        var env = comments().add("pch_demo00000001", "Check the total", "approvers_only");
        assertEquals("POST", last().method());
        assertEquals("/@portal/pending-approvals/pch_demo00000001/comments", last().target());
        JsonNode sent = M.readTree(last().body());
        assertEquals(2, sent.size());
        assertEquals("Check the total", sent.get("body").asText());
        assertEquals("approvers_only", sent.get("visibility").asText());
        Approvals.Comment c = env.data();
        assertEquals("acm_01", c.id());
        assertEquals("pch_demo00000001", c.caseId());
        assertEquals("usr_demo00000001", c.authorId());
        assertEquals("approvers_only", c.visibility());
        assertEquals("Check the total", c.body());
        assertEquals("2026-11-13T09:30:00.000Z", c.createdAt());
        assertEquals("req_1", env.meta().context().requestId());
    }

    @Test
    void addEncodesThePendingId() {
        handler = c -> new String[] {"201", "{\"data\":" + comment("acm_01", "everyone", "x") + "," + META + "}"};
        comments().add("a/b c", "x", "everyone");
        assertEquals("/@portal/pending-approvals/a%2Fb%20c/comments", last().target());
    }

    @Test
    void addChecksTheInputBeforeAnyRequest() {
        Approvals.Comments c = comments();
        for (String id : new String[] {null, ""}) {
            KError e = assertThrows(KError.class, () -> c.add(id, "hi", "everyone"));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        for (String body : new String[] {null, "", "x".repeat(4001)}) {
            KError e = assertThrows(KError.class, () -> c.add("pch_1", body, "everyone"));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        for (String vis : new String[] {null, "", "EVERYONE", "public", "approvers"}) {
            KError e = assertThrows(KError.class, () -> c.add("pch_1", "hi", vis));
            assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        }
        assertTrue(calls.isEmpty());
    }

    @Test
    void addAcceptsTheLimitsOfTheBodyLength() {
        handler = c -> new String[] {"201", "{\"data\":" + comment("acm_01", "everyone", "x") + "," + META + "}"};
        Approvals.Comments c = comments();
        c.add("pch_1", "x", "everyone");
        c.add("pch_1", "x".repeat(4000), "everyone");
        // 4000 characters outside the BMP are 8000 UTF-16 units but still 4000 characters.
        c.add("pch_1", "😀".repeat(4000), "everyone");
        assertEquals(3, calls.size());
        assertThrows(KError.class, () -> c.add("pch_1", "😀".repeat(4001), "everyone"));
        assertEquals(3, calls.size());
    }

    @Test
    void addNeedsAProjectSlug() {
        var k = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        KError e = assertThrows(KError.class, () -> k.approvals().comments().add("pch_1", "hi", "everyone"));
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", e.getCode());
        assertTrue(calls.isEmpty());
    }

    @Test
    void listFirstPageSendsNoQueryAndReadsNextCursor() {
        handler = c -> new String[] {"200", "{\"data\":[" + comment("acm_01", "everyone", "first") + ","
            + comment("acm_02", "approvers_only", "second") + "],\"nextCursor\":\"cur_2\"," + META + "}"};
        Approvals.CommentPage page = comments().list("pch_demo00000001");
        assertEquals("GET", last().method());
        assertEquals("/@portal/pending-approvals/pch_demo00000001/comments", last().target());
        assertEquals(2, page.data().size());
        assertEquals("first", page.data().get(0).body());
        assertEquals("approvers_only", page.data().get(1).visibility());
        assertEquals("cur_2", page.nextCursor());
        assertEquals("req_1", page.meta().context().requestId());
    }

    @Test
    void listSendsLimitAndCursorAndReadsTheLastPage() {
        handler = c -> new String[] {"200", "{\"data\":[" + comment("acm_03", "everyone", "third") + "],\"nextCursor\":null," + META + "}"};
        Approvals.CommentPage page = comments().list("pch_demo00000001", 2, "cur_2");
        assertEquals("/@portal/pending-approvals/pch_demo00000001/comments?limit=2&cursor=cur_2", last().target());
        assertEquals(1, page.data().size());
        assertNull(page.nextCursor());
    }

    @Test
    void listAnEmptyPageIsAnEmptyList() {
        handler = c -> new String[] {"200", "{\"data\":[],\"nextCursor\":null," + META + "}"};
        Approvals.CommentPage page = comments().list("pch_1");
        assertTrue(page.data().isEmpty());
        assertNull(page.nextCursor());
    }

    @Test
    void listChecksTheInputBeforeAnyRequest() {
        Approvals.Comments c = comments();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.list("")).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.list(null, null, null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.list("pch_1", 0, null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> c.list("pch_1", 201, null)).getCode());
        assertTrue(calls.isEmpty());
    }

    static String err(String code, String message) {
        return "{\"code\":\"" + code + "\",\"message\":\"" + message + "\",\"context\":{}}";
    }

    @Test
    void gatewayRefusalsPassThroughAsKError() {
        Approvals.Comments c = comments();
        String[][] cases = {
            {"403", "K9O_APPROVAL_COMMENT_NOT_ELIGIBLE"},
            {"409", "K9O_APPROVAL_COMMENT_CLOSED"},
            {"400", "K9O_INVALID_FIELD_VALUE"},
            {"404", "K9O_NOT_FOUND"},
            {"403", "K9O_RBAC_DENY"},
        };
        for (String[] k : cases) {
            handler = call -> new String[] {k[0], err(k[1], "refused")};
            KError e = assertThrows(KError.class, () -> c.add("pch_1", "hi", "everyone"));
            assertEquals(k[1], e.getCode());
            assertEquals(Integer.parseInt(k[0]), e.getStatus());
            KError l = assertThrows(KError.class, () -> c.list("pch_1"));
            assertEquals(k[1], l.getCode());
        }
    }
}
