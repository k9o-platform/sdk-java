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
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** audit().tail() over a scripted SSE stream, and audit().replay(). */
class AuditTailTest {

    static final ObjectMapper JSON = new ObjectMapper();

    record Reply(int status, String body, boolean hang) {
        static Reply sse(String body) { return new Reply(200, body, false); }
        static Reply stalled() { return new Reply(200, "retry: 10\n:ok\n\n", true); }
        static Reply code(int s) { return new Reply(s, "", false); }
    }

    record Sub(String query, String lastEventId) {}

    HttpServer server;
    String baseUrl;
    final List<Reply> script = new CopyOnWriteArrayList<>();
    final List<Sub> subs = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();
    final List<String> replayBodies = new CopyOnWriteArrayList<>();
    final List<String> replayAnswers = new CopyOnWriteArrayList<>();
    volatile int replayStatus = 200;

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/@portal/realtime/subscribe", ex -> {
            int n = hits.getAndIncrement();
            subs.add(new Sub(ex.getRequestURI().getRawQuery(), ex.getRequestHeaders().getFirst("Last-Event-ID")));
            Reply r = n < script.size() ? script.get(n) : script.get(script.size() - 1);
            if (r.status() != 200) {
                byte[] eb = "{\"code\":\"K9O_RBAC_DENY\",\"message\":\"no audit access\",\"context\":{}}".getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.status(), eb.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(eb); }
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            try {
                os.write(r.body().getBytes(StandardCharsets.UTF_8));
                os.flush();
                if (r.hang()) Thread.sleep(3000);
            } catch (IOException | InterruptedException ignored) {
            } finally {
                try { os.close(); } catch (IOException ignored) {}
            }
        });
        server.createContext("/@portal/realtime/replay", ex -> {
            int n = replayBodies.size();
            replayBodies.add(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String a = n < replayAnswers.size() ? replayAnswers.get(n) : "{\"data\":[],\"meta\":{}}";
            byte[] b = a.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(replayStatus, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    static final String HEAD = "retry: 10\n:ok\n\n";

    /** A frame as the gateway sends it: the SSE id and eventId are the publisher's, not the audit id. */
    static String frame(String id, String type) {
        return "id: pub_" + id + "\nevent: " + type + "\ndata: {\"topic\":\"audit.project." + type + "\",\"eventType\":\"" + type
            + "\",\"payload\":{\"auditEventId\":\"" + id + "\",\"type\":\"" + type
            + "\",\"actorType\":\"user\",\"occurredAt\":\"2026-10-27T10:00:00.000Z\",\"outcome\":\"success\"},"
            + "\"occurredAt\":\"2026-10-27T10:00:00.000Z\",\"eventId\":\"pub_" + id + "\"}\n\n";
    }

    /** A replay event as the gateway answers it. */
    static String rev(String id, String type, int seq) {
        return "{\"sequence\":\"" + seq + "\",\"topic\":\"audit.project." + type + "\",\"eventType\":\"" + type
            + "\",\"payload\":{\"auditEventId\":\"" + id + "\",\"type\":\"" + type
            + "\",\"actorType\":\"user\",\"occurredAt\":\"2026-10-27T10:02:00.000Z\",\"outcome\":null},\"occurredAt\":\"2026-10-27T10:02:00.000Z\"}";
    }

    static String page(boolean hasMore, String... events) {
        return "{\"data\":{\"topic\":\"audit.project.*\",\"events\":[" + String.join(",", events) + "],\"nextCursor\":null,\"hasMore\":"
            + hasMore + "},\"meta\":{}}";
    }

    Audit audit() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).apiKey("tok_secret").projectSlug("portal").build()).audit();
    }

    Audit.TailOptions fast() {
        return new Audit.TailOptions().reconnectDelay(Duration.ofMillis(10));
    }

    List<Audit.TailEvent> take(EventStream<Audit.TailEvent> s, int n) {
        List<Audit.TailEvent> out = new ArrayList<>();
        for (var ev : s) {
            out.add(ev);
            if (out.size() == n) break;
        }
        return out;
    }

    static List<String> ids(List<Audit.TailEvent> evs) { return evs.stream().map(Audit.TailEvent::auditEventId).toList(); }

    @Test
    void yieldsExactlyTheFiveFieldsOnTheAllTopic() {
        script.add(Reply.sse(HEAD + frame("evt_A", "data.write.access.insert")));
        try (var s = audit().tail(fast())) {
            var e = take(s, 1).get(0);
            assertEquals("evt_A", e.auditEventId());
            assertEquals("data.write.access.insert", e.type());
            assertEquals("user", e.actorType());
            assertEquals("2026-10-27T10:00:00.000Z", e.occurredAt());
            assertEquals("success", e.outcome());
        }
        assertEquals("topic=audit.project.*", java.net.URLDecoder.decode(subs.get(0).query(), StandardCharsets.UTF_8));
        assertNull(subs.get(0).lastEventId());
    }

    /** The frame captured live from the gateway, byte for byte. */
    static final String CAPTURED = "id: evt_TqfKnZdBRtggfnwJvuvroU\nevent: data.write.forge.scheduled_trigger.created\n"
        + "data: {\"topic\":\"audit.project.data.write.forge.scheduled_trigger.created\",\"eventType\":\"data.write.forge.scheduled_trigger.created\","
        + "\"payload\":{\"auditEventId\":\"evt_UNs3pW7TSadU\",\"type\":\"data.write.forge.scheduled_trigger.created\",\"actorType\":\"user\","
        + "\"occurredAt\":\"2026-10-27T19:31:40.636Z\",\"outcome\":null},\"occurredAt\":\"2026-10-27T19:31:40.636Z\","
        + "\"eventId\":\"evt_TqfKnZdBRtggfnwJvuvroU\"}\n\n";

    @Test
    void capturedLiveFrameIsFlattenedFromThePayload() {
        script.add(Reply.sse("retry: 1000\n:ok\n\n" + CAPTURED));
        script.add(Reply.sse("retry: 1000\n:ok\n\n" + CAPTURED));
        try (var s = audit().tail(fast())) {
            var e = take(s, 1).get(0);
            assertEquals(new Audit.TailEvent("evt_UNs3pW7TSadU", "data.write.forge.scheduled_trigger.created", "user",
                "2026-10-27T19:31:40.636Z", null, "evt_TqfKnZdBRtggfnwJvuvroU", null), e);
        }
    }

    @Test
    void resumesWithTheSseIdNotTheAuditId() {
        script.add(Reply.sse("retry: 10\n:ok\n\n" + CAPTURED));
        script.add(Reply.sse("retry: 10\n:ok\n\n" + CAPTURED + frame("evt_next", "t")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_UNs3pW7TSadU", "evt_next"), ids(take(s, 2)));
        }
        assertEquals("evt_TqfKnZdBRtggfnwJvuvroU", subs.get(1).lastEventId());
    }

    @Test
    void oneTypeUsesTheTypeTopic() {
        script.add(Reply.sse(HEAD + frame("evt_A", "data.write.access.insert")));
        try (var s = audit().tail(fast().types(List.of("data.write.access.insert")))) {
            assertEquals(1, take(s, 1).size());
        }
        assertEquals("topic=audit.project.data.write.access.insert", subs.get(0).query());
    }

    @Test
    void severalTypesFollowAllAndKeepTheListedOnes() {
        script.add(Reply.sse(HEAD + frame("evt_1", "a.one") + frame("evt_2", "b.two") + frame("evt_3", "c.three") + frame("evt_4", "a.one")));
        try (var s = audit().tail(fast().types(List.of("a.one", "c.three")))) {
            assertEquals(List.of("evt_1", "evt_3", "evt_4"), ids(take(s, 3)));
        }
        assertEquals("topic=audit.project.*", java.net.URLDecoder.decode(subs.get(0).query(), StandardCharsets.UTF_8));
    }

    @Test
    void wardenCheckpointIsNeverDelivered() {
        script.add(Reply.sse(HEAD + frame("evt_1", "a.one") + frame("evt_2", "warden.checkpoint") + frame("evt_3", "a.one")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_3"), ids(take(s, 2)));
        }
    }

    @Test
    void reconnectsWithLastEventIdAndDropsDuplicates() {
        script.add(Reply.sse(HEAD + frame("evt_A", "t") + frame("evt_B", "t")));
        script.add(Reply.sse(HEAD + frame("evt_A", "t") + frame("evt_B", "t") + frame("evt_C", "t")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_A", "evt_B", "evt_C"), ids(take(s, 3)));
        }
        assertNull(subs.get(0).lastEventId());
        assertEquals("pub_evt_B", subs.get(1).lastEventId());
        assertTrue(replayBodies.isEmpty(), "a plain resume needs no replay");
    }

    @Test
    void heartbeatsAndCommentsAreNotEvents() {
        script.add(Reply.sse(HEAD + ":hb\n\n:hb\n\n" + frame("evt_A", "t") + ":hb\n\n" + frame("evt_B", "t")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_A", "evt_B"), ids(take(s, 2)));
        }
    }

    @Test
    void resyncReplayLedgerCatchesUpThroughReplayThenContinuesLive() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + frame("evt_2", "t")
            + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_4", "t") + frame("evt_5", "t")));
        replayAnswers.add(page(false, rev("evt_3", "t", 3), rev("evt_cp", "warden.checkpoint", 4), rev("evt_4", "t", 5)));
        List<String> actions = new ArrayList<>();
        try (var s = audit().tail(fast().onResync(r -> actions.add(r.action())))) {
            assertEquals(List.of("evt_1", "evt_2", "evt_3", "evt_4", "evt_5"), ids(take(s, 5)),
                "evt_4 arrives by replay and again live: delivered once");
        }
        assertEquals(List.of("replay-ledger"), actions);
        assertEquals(1, replayBodies.size());
        JsonNode b = JSON.readTree(replayBodies.get(0));
        assertEquals("audit.project.*", b.get("topic").asText());
        assertEquals("evt_2", b.get("afterAuditEventId").asText());
        assertFalse(b.has("fromSequence"));
        assertNull(subs.get(1).lastEventId(), "after a resync the tail reconnects fresh, never with a stale Last-Event-ID");
        assertEquals(1000, b.get("limit").asInt());
    }

    @Test
    void replayOnResyncFalseLeavesTheCatchUpToTheCaller() {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_9", "t")));
        List<String> mine = new ArrayList<>();
        var opts = fast().replayOnResync(false).onResync(r -> mine.add(r.action()));
        try (var s = audit().tail(opts)) {
            assertEquals(List.of("evt_1", "evt_9"), ids(take(s, 2)));
        }
        assertEquals(List.of("replay-ledger"), mine);
        assertTrue(replayBodies.isEmpty());
    }

    @Test
    void resyncOfAnotherActionDoesNotReplay() {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"reread-source\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_9", "t")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_9"), ids(take(s, 2)));
        }
        assertTrue(replayBodies.isEmpty());
    }

    @Test
    void resyncBeforeAnyEventHasNothingToReplayFrom() {
        script.add(Reply.sse(HEAD + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_9", "t")));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_9"), ids(take(s, 1)));
        }
        assertTrue(replayBodies.isEmpty());
    }

    @Test
    void aFailingReplayIsTheTypedError() {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n"));
        replayStatus = 400;
        replayAnswers.add("{\"code\":\"K9O_INVALID_CURSOR\",\"message\":\"not in the log\",\"context\":{}}");
        var s = audit().tail(fast());
        try {
            var e = assertThrows(KError.class, () -> take(s, 2));
            assertEquals("K9O_INVALID_CURSOR", e.getCode());
        } finally {
            s.close();
        }
    }

    @Test
    void anIdleConnectionIsDroppedAndReopened() {
        script.add(Reply.stalled());
        script.add(Reply.sse(HEAD + frame("evt_1", "t")));
        try (var s = audit().tail(fast().idleTimeout(Duration.ofMillis(150)))) {
            assertEquals(List.of("evt_1"), ids(take(s, 1)));
        }
        assertTrue(hits.get() >= 2, "the idle stream was reopened");
    }

    @Test
    void noAuditAccessIsATypedErrorAndEndsTheTail() {
        script.add(Reply.code(403));
        var s = audit().tail(fast());
        try {
            var e = assertThrows(KError.class, () -> take(s, 1));
            assertEquals("K9O_RBAC_DENY", e.getCode());
            assertEquals(403, e.getStatus());
            assertFalse(s.iterator().hasNext());
        } finally {
            s.close();
        }
        assertEquals(1, hits.get());
    }

    @Test
    void tailChecksTypesAndProjectContext() {
        for (var bad : List.of(List.of(""), List.of("a b"), List.of("a.*"))) {
            assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> audit().tail(fast().types(bad))).getCode());
        }
        var noProject = K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).build()).audit();
        assertEquals("K9O_MISSING_PROJECT_CONTEXT", assertThrows(KError.class, () -> noProject.tail()).getCode());
        assertEquals(0, hits.get());
    }

    // ---- replay

    @Test
    void replayWireAfterAnEventId() throws Exception {
        replayAnswers.add(page(true, rev("evt_9", "policy.updated", 42)).replace("\"nextCursor\":null", "\"nextCursor\":\"c1\""));
        var out = audit().replay(new Audit.ReplayOpts(null, "evt_8", null, null, 500));
        JsonNode b = JSON.readTree(replayBodies.get(0));
        assertEquals("audit.project.*", b.get("topic").asText());
        assertEquals("evt_8", b.get("afterAuditEventId").asText());
        assertEquals(500, b.get("limit").asInt());
        assertTrue(out.data().hasMore());
        assertEquals("c1", out.data().nextCursor());
        assertEquals(1, out.data().events().size());
        var e = out.data().events().get(0);
        assertEquals("evt_9", e.auditEventId());
        assertEquals("policy.updated", e.type());
        assertEquals("user", e.actorType());
        assertEquals("2026-10-27T10:02:00.000Z", e.occurredAt());
        assertNull(e.outcome());
        assertEquals("42", e.sequence());
        assertEquals("audit.project.policy.updated", e.topic());
    }

    @Test
    void capturedReplayAnswerIsTyped() throws Exception {
        replayAnswers.add("{\"data\":{\"topic\":\"audit.project.*\",\"events\":[{\"sequence\":\"1\",\"topic\":\"audit.project.data.write.pulse_stream.created\","
            + "\"eventType\":\"data.write.pulse_stream.created\",\"payload\":{\"auditEventId\":\"evt_UGgF77wit3Xx\",\"type\":\"data.write.pulse_stream.created\","
            + "\"actorType\":\"user\",\"occurredAt\":\"2026-10-27T19:00:00.000Z\",\"outcome\":null},\"occurredAt\":\"2026-10-27T19:00:00.000Z\"}],"
            + "\"nextCursor\":null,\"hasMore\":false},\"meta\":{}}");
        var out = audit().replay(Audit.ReplayOpts.fromSequence(0));
        assertEquals(0, JSON.readTree(replayBodies.get(0)).get("fromSequence").asInt());
        assertEquals(new Audit.ReplayEvent("evt_UGgF77wit3Xx", "data.write.pulse_stream.created", "user", "2026-10-27T19:00:00.000Z",
            null, "1", "audit.project.data.write.pulse_stream.created"), out.data().events().get(0));
        assertFalse(out.data().hasMore());
        assertNull(out.data().nextCursor());
    }

    @Test
    void catchUpPagesWhileHasMoreAfterTheLastEventOfEachPage() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_5", "t")));
        replayAnswers.add(page(true, rev("evt_2", "t", 2), rev("evt_3", "t", 3)));
        replayAnswers.add(page(false, rev("evt_4", "t", 4)));
        try (var s = audit().tail(fast())) {
            var got = take(s, 5);
            assertEquals(List.of("evt_1", "evt_2", "evt_3", "evt_4", "evt_5"), ids(got));
            assertEquals("2", got.get(1).sequence());
        }
        assertEquals(2, replayBodies.size());
        assertEquals("evt_1", JSON.readTree(replayBodies.get(0)).get("afterAuditEventId").asText());
        assertEquals("evt_3", JSON.readTree(replayBodies.get(1)).get("afterAuditEventId").asText());
    }

    @Test
    void replayChecksArguments() {
        var a = audit();
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.replay(null)).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.replay(new Audit.ReplayOpts(null, null, null, null, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.replay(new Audit.ReplayOpts(null, "evt_1", 1L, null, null))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.replay(Audit.ReplayOpts.after("nope"))).getCode());
        assertEquals("K9O_INVALID_FIELD_VALUE", assertThrows(KError.class, () -> a.replay(new Audit.ReplayOpts(null, "evt_1", null, null, 10_001))).getCode());
        assertTrue(replayBodies.isEmpty());
    }

    @Test
    void replayInvalidCursorAndForbiddenAreTyped() {
        replayStatus = 400;
        replayAnswers.add("{\"code\":\"K9O_INVALID_CURSOR\",\"message\":\"not in the log\",\"context\":{}}");
        assertEquals("K9O_INVALID_CURSOR", assertThrows(KError.class, () -> audit().replay(Audit.ReplayOpts.after("evt_nope"))).getCode());
    }

    static final String RESYNC = "event: resync\ndata: {\"reason\":\"gap\",\"action\":\"replay-ledger\"}\n\n";

    @Test
    void anEventPublishedAfterTheReplayEndsIsInTheFreshBacklogAndYieldedOnce() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + RESYNC));
        // E (evt_E) was published after the replay's last event (evt_3) but before the fresh stream's first frame.
        script.add(Reply.sse(HEAD + frame("evt_2", "t") + frame("evt_3", "t") + frame("evt_E", "t") + frame("evt_live", "t")));
        replayAnswers.add(page(false, rev("evt_2", "t", 2), rev("evt_3", "t", 3)));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_2", "evt_3", "evt_E", "evt_live"), ids(take(s, 5)));
        }
        assertNull(subs.get(1).lastEventId());
    }

    @Test
    void framesBufferedDuringTheReplayComeAfterTheReplayEventsDeduped() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + RESYNC));
        script.add(Reply.sse(HEAD + frame("evt_3", "t") + frame("evt_4", "t")));
        replayAnswers.add(page(false, rev("evt_2", "t", 2), rev("evt_3", "t", 3)));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_2", "evt_3", "evt_4"), ids(take(s, 4)));
        }
        assertEquals(1, replayBodies.size());
        assertEquals(2, hits.get(), "connected once more, before the replay ran");
    }

    @Test
    void aDoubleResyncBacksOffAndKeepsTrying() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t") + RESYNC));
        script.add(Reply.sse(HEAD + RESYNC));
        script.add(Reply.sse(HEAD + RESYNC));
        script.add(Reply.sse(HEAD + frame("evt_2", "t")));
        replayAnswers.add(page(false));
        long t0 = System.nanoTime();
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_2"), ids(take(s, 2)));
        }
        assertEquals(4, hits.get());
        assertEquals(1, replayBodies.size(), "one catch-up per run of resyncs");
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 30, "reconnects are delayed, not spun");
    }

    @Test
    void replayDefaultLimitIs1000AndOutOfRangeIsRefusedClientSide() throws Exception {
        replayAnswers.add(page(false));
        audit().replay(Audit.ReplayOpts.after("evt_1"));
        assertEquals(1000, JSON.readTree(replayBodies.get(0)).get("limit").asInt());
        replayAnswers.add(page(false));
        audit().replay(new Audit.ReplayOpts(null, "evt_1", null, null, 10_000));
        assertEquals(10_000, JSON.readTree(replayBodies.get(1)).get("limit").asInt());
        for (int bad : new int[] {10_001, 0}) {
            assertEquals("K9O_INVALID_FIELD_VALUE",
                assertThrows(KError.class, () -> audit().replay(new Audit.ReplayOpts(null, "evt_1", null, null, bad))).getCode());
        }
        assertEquals(2, replayBodies.size());
    }

    @Test
    void capturedHeadResyncThenLiveEventOnTheSameConnectionIsInformational() {
        script.add(Reply.sse("retry: 1000\n:ok\n\nevent: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":75,\"action\":\"replay-ledger\"}\n\n" + CAPTURED));
        List<String> called = new ArrayList<>();
        try (var s = audit().tail(fast().onResync(r -> called.add(r.action())))) {
            assertEquals(List.of("evt_UNs3pW7TSadU"), ids(take(s, 1)));
        }
        assertTrue(called.isEmpty(), "onResync is not called for the head resync");
        assertTrue(replayBodies.isEmpty());
        assertEquals(1, hits.get(), "no reconnect");
    }

    @Test
    void aStaleResumeIsAnsweredWithResyncThenTheTailReconnectsFreshAndReplaysOnce() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_1", "t")));
        script.add(Reply.sse(HEAD + RESYNC));
        script.add(Reply.sse(HEAD + RESYNC + frame("evt_3", "t") + frame("evt_4", "t")));
        replayAnswers.add(page(false, rev("evt_2", "t", 2), rev("evt_3", "t", 3)));
        try (var s = audit().tail(fast())) {
            assertEquals(List.of("evt_1", "evt_2", "evt_3", "evt_4"), ids(take(s, 4)));
        }
        assertEquals("pub_evt_1", subs.get(1).lastEventId());
        assertNull(subs.get(2).lastEventId());
        assertEquals(1, replayBodies.size());
        assertEquals("evt_1", JSON.readTree(replayBodies.get(0)).get("afterAuditEventId").asText());
    }
}
