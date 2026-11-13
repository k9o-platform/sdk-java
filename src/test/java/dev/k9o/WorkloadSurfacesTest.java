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

/**
 * requireApproval on single-record writes, function triggers, schedules and end-user role grants:
 * exact method, path, query and body of every call, plus the discriminator and the 204 / revoked-false answers.
 */
class WorkloadSurfacesTest {

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
            int status = Integer.parseInt(r[0]);
            if (status == 204) {
                ex.sendResponseHeaders(204, -1);
            } else {
                ex.sendResponseHeaders(status, b.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            }
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

    void expectLast(String method, String target, String json) throws IOException {
        Call c = calls.get(calls.size() - 1);
        assertEquals(method, c.method());
        assertEquals(target, c.target());
        if (json == null) assertEquals("", c.body());
        else assertEquals(M.readTree(json), M.readTree(c.body()), c.body());
    }

    static final String QUEUED_INSERT = "{\"data\":{\"id\":\"apr_5oLshD2MVT27\",\"status\":\"pending_approval\",\"collection\":\"conformance_kv\","
        + "\"operation\":\"insert\",\"makerId\":\"usr_conformance0001\",\"proposedPayload\":{\"id\":\"contract2-probe-0001\",\"val\":\"contract2-probe\"},"
        + "\"targetRecordId\":null},\"meta\":{\"cost\":{},\"timing\":{},\"context\":{\"requestId\":\"req_1\",\"traceId\":\"req_1\",\"projectId\":\"proj_1\"},"
        + "\"approval\":{\"status\":\"pending_approval\"}}}";

    public record Row(String id, String val, String status) {}

    // ---- requireApproval ----

    @Test
    void queuedInsertIsRecognised() throws IOException {
        handler = c -> new String[] {"202", QUEUED_INSERT};
        var result = client().from("conformance_kv")
            .insert(Map.of("id", "contract2-probe-0001", "val", "contract2-probe"))
            .requireApproval().execute(Row.class);
        expectLast("POST", "/@portal/data/conformance_kv?requireApproval=true",
            "{\"id\":\"contract2-probe-0001\",\"val\":\"contract2-probe\"}");
        assertTrue(result.isQueued());
        var q = assertInstanceOf(GatedWrite.Queued.class, result);
        Approvals.QueuedChange change = ((GatedWrite.Queued<Row>) q).envelope().data();
        assertEquals("apr_5oLshD2MVT27", change.id());
        assertEquals("pending_approval", change.status());
        assertEquals("conformance_kv", change.collection());
        assertEquals("insert", change.operation());
        assertEquals("usr_conformance0001", change.makerId());
        assertEquals("contract2-probe", change.proposedPayload().get("val"));
        assertNull(change.targetRecordId());
        assertEquals("pending_approval", ((GatedWrite.Queued<Row>) q).envelope().meta().approval().status());
    }

    @Test
    void autoApprovedInsertIsAppliedNotQueued() throws IOException {
        // An auto_approve policy applies the write even with the flag: 201 and a plain row, no meta.approval.
        handler = c -> new String[] {"201", "{\"data\":{\"id\":\"r1\",\"val\":\"x\",\"status\":\"pending_approval\"},\"meta\":{}}"};
        var result = client().from("conformance_kv").insert(Map.of("id", "r1")).requireApproval().execute(Row.class);
        assertFalse(result.isQueued());
        var applied = assertInstanceOf(GatedWrite.Applied.class, result);
        // a row whose own status column reads pending_approval is still an applied row
        assertEquals("r1", ((Row) ((GatedWrite.Applied<?>) applied).envelope().data()).id());
    }

    @Test
    void updateAndDeleteSendTheFlagAndUnqueuedWritesDoNot() throws IOException {
        String queuedUpdate = "{\"data\":{\"id\":\"apr_u\",\"status\":\"pending_approval\",\"collection\":\"orders\",\"operation\":\"update\","
            + "\"makerId\":\"usr_1\",\"proposedPayload\":{\"total\":5},\"targetRecordId\":\"o-1\"},\"meta\":{\"approval\":{\"status\":\"pending_approval\"}}}";
        String queuedDelete = "{\"data\":{\"id\":\"apr_d\",\"status\":\"pending_approval\",\"collection\":\"orders\",\"operation\":\"delete\","
            + "\"makerId\":\"usr_1\",\"proposedPayload\":{},\"targetRecordId\":\"o-1\"},\"meta\":{\"approval\":{\"status\":\"pending_approval\"}}}";
        handler = c -> new String[] {"202", c.method().equals("PATCH") ? queuedUpdate : queuedDelete};

        var upd = client().from("orders").update(Map.of("total", 5)).eq("id", "o-1").requireApproval().execute(Row.class);
        expectLast("PATCH", "/@portal/data/orders/o-1?requireApproval=true", "{\"total\":5}");
        assertTrue(upd.isQueued());
        assertEquals("o-1", ((GatedWrite.Queued<Row>) upd).envelope().data().targetRecordId());

        var del = client().from("orders").delete().eq("id", "o-1").requireApproval().execute(Row.class);
        expectLast("DELETE", "/@portal/data/orders/o-1?requireApproval=true", null);
        assertTrue(del.isQueued());
        assertEquals("delete", ((GatedWrite.Queued<Row>) del).envelope().data().operation());
        assertTrue(((GatedWrite.Queued<Row>) del).envelope().data().proposedPayload().isEmpty());

        // Without the modifier no query is sent and the answer is the plain envelope.
        handler = c -> new String[] {"200", "{\"data\":{\"id\":\"o-1\",\"val\":\"v\"},\"meta\":{}}"};
        var plain = client().from("orders").update(Map.of("total", 5)).eq("id", "o-1").execute(Row.class);
        expectLast("PATCH", "/@portal/data/orders/o-1", "{\"total\":5}");
        assertEquals("o-1", plain.data().id());
        client().from("orders").delete().eq("id", "o-1").execute(Row.class);
        expectLast("DELETE", "/@portal/data/orders/o-1", null);
        handler = c -> new String[] {"201", "{\"data\":{\"id\":\"o-2\"},\"meta\":{}}"};
        client().from("orders").insert(Map.of("id", "o-2"), Row.class);
        expectLast("POST", "/@portal/data/orders", "{\"id\":\"o-2\"}");
        client().from("orders").insert(Map.of("id", "o-2")).execute(Row.class);
        expectLast("POST", "/@portal/data/orders", "{\"id\":\"o-2\"}");
    }

    @Test
    void requireApprovalOnUpdateNeedsTheIdFilter() {
        KError e = assertThrows(KError.class, () -> client().from("orders").update(Map.of("a", 1)).requireApproval());
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
        assertThrows(KError.class, () -> client().from("orders").delete().requireApproval());
        assertTrue(calls.isEmpty());
    }

    @Test
    void invalidFlagAnswerIsTyped() {
        handler = c -> new String[] {"400", "{\"code\":\"K9O_INVALID_FIELD_VALUE\",\"message\":\"requireApproval must be 'true' or 'false'\",\"context\":{}}"};
        KError e = assertThrows(KError.class, () -> client().from("orders").insert(Map.of("id", "x")).requireApproval().execute(Row.class));
        assertEquals("K9O_INVALID_FIELD_VALUE", e.getCode());
    }

    // ---- triggers ----

    static final String TRIGGER = "{\"data\":{\"id\":\"0b8f6a52-3f0e-4c8e-9a47-5d2b0f1c7e11\",\"functionName\":\"w4c-probe\",\"collection\":\"orders\","
        + "\"events\":[\"insert\",\"update\"],\"filter\":null,\"enabled\":true,\"logOkInvocations\":false,\"createdAt\":\"2026-10-26T17:59:56.815Z\"},\"meta\":{}}";

    @Test
    void triggers() throws IOException {
        var t = client().functions().triggers();

        handler = c -> new String[] {"200", "{\"data\":[],\"meta\":{\"cost\":{},\"timing\":{}}}"};
        assertTrue(t.list("w4c-probe").data().isEmpty());
        expectLast("GET", "/@portal/functions/w4c-probe/triggers", null);

        handler = c -> new String[] {"201", TRIGGER};
        var created = t.create("w4c-probe", new Functions.CreateTriggerInput("orders", List.of("insert", "update"))).data();
        expectLast("POST", "/@portal/functions/w4c-probe/triggers", "{\"collection\":\"orders\",\"events\":[\"insert\",\"update\"]}");
        assertEquals("0b8f6a52-3f0e-4c8e-9a47-5d2b0f1c7e11", created.id());
        assertEquals(List.of("insert", "update"), created.events());
        assertNull(created.filter());
        assertTrue(created.enabled());

        t.create("w4c-probe", new Functions.CreateTriggerInput("orders", List.of("delete"),
            Map.of("eq", List.of("resource.status", "paid")), false, true));
        expectLast("POST", "/@portal/functions/w4c-probe/triggers",
            "{\"collection\":\"orders\",\"events\":[\"delete\"],\"filter\":{\"eq\":[\"resource.status\",\"paid\"]},\"enabled\":false,\"logOkInvocations\":true}");

        handler = c -> new String[] {"200", TRIGGER};
        t.update("w4c-probe", "0b8f6a52", new Functions.UpdateTriggerInput().enabled(false));
        expectLast("PATCH", "/@portal/functions/w4c-probe/triggers/0b8f6a52", "{\"enabled\":false}");
        t.update("w4c-probe", "0b8f6a52", new Functions.UpdateTriggerInput().events(List.of("upsert")).clearFilter().logOkInvocations(true));
        expectLast("PATCH", "/@portal/functions/w4c-probe/triggers/0b8f6a52", "{\"events\":[\"upsert\"],\"filter\":null,\"logOkInvocations\":true}");
        t.update("w4c-probe", "0b8f6a52", new Functions.UpdateTriggerInput().filter(Map.of("eq", List.of("a", 1))));
        expectLast("PATCH", "/@portal/functions/w4c-probe/triggers/0b8f6a52", "{\"filter\":{\"eq\":[\"a\",1]}}");

        handler = c -> new String[] {"204", ""};
        var gone = t.delete("w4c-probe", "0b8f6a52");
        expectLast("DELETE", "/@portal/functions/w4c-probe/triggers/0b8f6a52", null);
        assertNull(gone.data());
    }

    @Test
    void triggerInputsAreCheckedBeforeSending() {
        var t = client().functions().triggers();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class,
            () -> t.create("f", new Functions.CreateTriggerInput("orders", List.of()))).getCode());
        assertThrows(KError.class, () -> t.create("f", new Functions.CreateTriggerInput("orders", List.of("insert", "update", "delete", "upsert", "insert"))));
        assertThrows(KError.class, () -> t.create("f", new Functions.CreateTriggerInput("orders", List.of("truncate"))));
        assertThrows(KError.class, () -> t.create("f", new Functions.CreateTriggerInput("1bad-name", List.of("insert"))));
        assertThrows(KError.class, () -> t.update("f", "id", new Functions.UpdateTriggerInput()));
        assertThrows(KError.class, () -> t.update("f", "id", new Functions.UpdateTriggerInput().events(List.of("nope"))));
        assertThrows(KError.class, () -> t.delete("f", ""));
        assertTrue(calls.isEmpty());
    }

    // ---- schedules ----

    static final String SCHEDULE = "{\"data\":{\"id\":\"s-1\",\"functionName\":\"nightly-report\",\"scheduleExpression\":\"0 0 * * *\","
        + "\"scheduleTimezone\":\"Europe/Berlin\",\"catchupPolicy\":\"skip\",\"collection\":null,\"enabled\":true,\"status\":\"active\","
        + "\"logOkInvocations\":false,\"createdAt\":\"2026-10-26T17:59:56.815Z\",\"lastInvocationAt\":null,\"nextInvocationAt\":null},\"meta\":{}}";

    @Test
    void schedules() throws IOException {
        var s = client().schedules();

        handler = c -> new String[] {"200", "{\"data\":[],\"meta\":{\"cursor\":{\"next\":null}}}"};
        var page = s.list();
        expectLast("GET", "/@portal/forge/schedules", null);
        assertTrue(page.data().isEmpty());
        assertNull(page.meta().cursor().next());
        s.list("active", "nightly-report", 2, "abc");
        expectLast("GET", "/@portal/forge/schedules?status=active&function_name=nightly-report&limit=2&cursor=abc", null);
        s.list(null, "fn", null, null);
        expectLast("GET", "/@portal/forge/schedules?function_name=fn", null);

        handler = c -> new String[] {"200", SCHEDULE};
        var got = s.get("s-1").data();
        expectLast("GET", "/@portal/forge/schedules/s-1", null);
        assertEquals("Europe/Berlin", got.scheduleTimezone());
        assertEquals("active", got.status());

        handler = c -> new String[] {"201", SCHEDULE};
        s.create(new Schedules.CreateInput("nightly-report", "@daily"));
        expectLast("POST", "/@portal/forge/schedules", "{\"functionName\":\"nightly-report\",\"scheduleExpression\":\"@daily\"}");
        s.create(new Schedules.CreateInput("nightly-report", "0 0 * * *", "Europe/Berlin", "run_once", "orders", false, true));
        expectLast("POST", "/@portal/forge/schedules",
            "{\"functionName\":\"nightly-report\",\"scheduleExpression\":\"0 0 * * *\",\"scheduleTimezone\":\"Europe/Berlin\",\"catchupPolicy\":\"run_once\","
                + "\"collection\":\"orders\",\"enabled\":false,\"logOkInvocations\":true}");

        handler = c -> new String[] {"200", SCHEDULE};
        s.update("s-1", new Schedules.UpdateInput(null, null, null, "paused", null, null));
        expectLast("PATCH", "/@portal/forge/schedules/s-1", "{\"status\":\"paused\"}");

        handler = c -> new String[] {"200", "{\"data\":[{\"id\":\"r1\",\"triggerId\":\"s-1\",\"functionName\":\"nightly-report\",\"collection\":null,\"event\":\"schedule\","
            + "\"status\":\"ok\",\"attemptCount\":1,\"errorDetail\":null,\"errorOutput\":null,\"recordId\":null,\"triggeredAt\":\"t\",\"completedAt\":\"t2\"}],\"meta\":{\"cursor\":{\"next\":\"n1\"}}}"};
        var runs = s.runs("s-1", 5, "c0");
        expectLast("GET", "/@portal/forge/schedules/s-1/runs?limit=5&cursor=c0", null);
        assertEquals(1, runs.data().get(0).attemptCount());
        assertEquals("n1", runs.meta().cursor().next());
        s.runs("s-1");
        expectLast("GET", "/@portal/forge/schedules/s-1/runs", null);

        handler = c -> new String[] {"202", "{\"data\":{\"runId\":\"r9\",\"queued\":true},\"meta\":{}}"};
        var now = s.runNow("s-1");
        expectLast("POST", "/@portal/forge/schedules/s-1/run-now", null);
        assertEquals("r9", now.data().get("runId").asText());

        handler = c -> new String[] {"204", ""};
        var gone = s.delete("s-1");
        expectLast("DELETE", "/@portal/forge/schedules/s-1", null);
        assertNull(gone.data());
    }

    @Test
    void scheduleInputsAreCheckedBeforeSending() {
        var s = client().schedules();
        assertThrows(KError.class, () -> s.list(null, null, 0, null));
        assertThrows(KError.class, () -> s.list(null, null, 201, null));
        assertThrows(KError.class, () -> s.runs("s-1", 201, null));
        assertThrows(KError.class, () -> s.update("s-1", new Schedules.UpdateInput(null, null, null, null, null, null)));
        assertThrows(KError.class, () -> s.get(""));
        assertTrue(calls.isEmpty());
    }

    // ---- end users ----

    @Test
    void endUsers() throws IOException {
        var u = client().endUsers();
        String user = "{\"id\":\"eu_YmGE3Z6nkv5i\",\"email\":\"user@example.com\",\"emailVerifiedAt\":null,\"status\":\"active\","
            + "\"createdAt\":\"2026-10-09T12:15:13.430Z\",\"lastSigninAt\":null,\"signInMethods\":[\"password\",\"magic_link\"],\"region\":null}";

        handler = c -> new String[] {"200", "{\"data\":[" + user + "],\"meta\":{\"cursor\":{\"next\":\"nx\"}}}"};
        var list = u.list();
        expectLast("GET", "/@portal/end-users", null);
        assertEquals("eu_YmGE3Z6nkv5i", list.data().get(0).id());
        assertEquals(List.of("password", "magic_link"), list.data().get(0).signInMethods());
        assertEquals("nx", list.meta().cursor().next());
        u.list("all", "user@", 2, "cur");
        expectLast("GET", "/@portal/end-users?status=all&q=user%40&limit=2&cursor=cur", null);

        handler = c -> new String[] {"200", "{\"data\":" + user + ",\"meta\":{}}"};
        assertEquals("active", u.get("eu_YmGE3Z6nkv5i").data().status());
        expectLast("GET", "/@portal/end-users/eu_YmGE3Z6nkv5i", null);
    }

    @Test
    void roleGrants() throws IOException {
        var g = client().endUsers().roleGrants();

        handler = c -> new String[] {"200", "{\"data\":{\"grants\":[]},\"meta\":{}}"};
        assertTrue(g.list().data().isEmpty());
        expectLast("GET", "/@portal/end-user-role-grants", null);
        assertTrue(g.list("eu_x").data().isEmpty());
        expectLast("GET", "/@portal/end-user-role-grants?endUserId=eu_x", null);

        String grant = "{\"id\":\"eurg_AbCdEf123456\",\"endUserId\":\"eu_YmGE3Z6nkv5i\",\"role\":\"billing-manager\","
            + "\"grantedAt\":\"2026-10-26T17:59:56.815Z\",\"grantedBy\":\"usr_conformance0001\"}";
        handler = c -> new String[] {"200", "{\"data\":{\"grants\":[" + grant + "]},\"meta\":{}}"};
        assertEquals("billing-manager", g.list().data().get(0).role());

        handler = c -> new String[] {"200", "{\"data\":{\"grant\":" + grant + "},\"meta\":{}}"};
        var granted = g.grant("eu_YmGE3Z6nkv5i", "billing-manager").data();
        expectLast("POST", "/@portal/end-user-role-grants", "{\"endUserId\":\"eu_YmGE3Z6nkv5i\",\"role\":\"billing-manager\"}");
        assertEquals("eurg_AbCdEf123456", granted.id());
        assertEquals("usr_conformance0001", granted.grantedBy());

        handler = c -> new String[] {"200", "{\"data\":{\"revoked\":true,\"revokedAt\":\"2026-10-26T18:00:00.000Z\"},\"meta\":{}}"};
        var revoked = g.revoke("eurg_AbCdEf123456").data();
        expectLast("DELETE", "/@portal/end-user-role-grants/eurg_AbCdEf123456", null);
        assertTrue(revoked.revoked());
        assertEquals("2026-10-26T18:00:00.000Z", revoked.revokedAt());

        // Unknown or already revoked: 200 with revoked false, not an error.
        handler = c -> new String[] {"200", "{\"data\":{\"revoked\":false,\"revokedAt\":null},\"meta\":{}}"};
        var again = g.revoke("eurg_AbCdEf123456").data();
        assertFalse(again.revoked());
        assertNull(again.revokedAt());
    }

    @Test
    void roleGrantInputsAreCheckedBeforeSending() {
        var g = client().endUsers().roleGrants();
        assertThrows(KError.class, () -> g.grant("", "r"));
        assertThrows(KError.class, () -> g.grant("eu_1", ""));
        assertThrows(KError.class, () -> g.grant("eu_1", "x".repeat(129)));
        assertThrows(KError.class, () -> g.grant("e".repeat(65), "r"));
        assertThrows(KError.class, () -> g.revoke(""));
        assertTrue(calls.isEmpty());
    }

    @Test
    void missingProjectIsTyped() {
        var c = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.schedules().list()).getCode());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.endUsers().list()).getCode());
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> c.functions().triggers().list("f")).getCode());
    }
}
