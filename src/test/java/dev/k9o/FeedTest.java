package dev.k9o;

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

/** Change feed resume: reconnect, Last-Event-ID, dedupe, resync, heartbeat, idle timeout. */
class FeedTest {

    /** One scripted response: a status and a body, optionally held open without sending (idle). */
    record Reply(int status, String body, boolean hang) {
        static Reply sse(String body) { return new Reply(200, body, false); }
        static Reply code(int s) { return new Reply(s, "", false); }
        static Reply stalled() { return new Reply(200, "retry: 10\n:ok\n\n", true); }
    }

    HttpServer server;
    String baseUrl;
    final List<Reply> script = new CopyOnWriteArrayList<>();
    final List<String> lastEventIds = new CopyOnWriteArrayList<>();
    final AtomicInteger hits = new AtomicInteger();

    @BeforeEach
    void setUp() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/@portal/realtime/subscribe", ex -> {
            int n = hits.getAndIncrement();
            String h = ex.getRequestHeaders().getFirst("Last-Event-ID");
            lastEventIds.add(h == null ? "<none>" : h);
            Reply r = n < script.size() ? script.get(n) : script.get(script.size() - 1);
            byte[] b = r.body().getBytes(StandardCharsets.UTF_8);
            if (r.status() != 200) {
                String err = "{\"error\":{\"code\":\"K9O_X\",\"message\":\"m\"}}";
                byte[] eb = err.getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "application/json");
                ex.sendResponseHeaders(r.status(), eb.length);
                try (OutputStream os = ex.getResponseBody()) { os.write(eb); }
                return;
            }
            ex.getResponseHeaders().add("Content-Type", "text/event-stream");
            ex.sendResponseHeaders(200, 0);
            OutputStream os = ex.getResponseBody();
            try {
                os.write(b);
                os.flush();
                if (r.hang()) Thread.sleep(3000);
            } catch (IOException | InterruptedException ignored) {
            } finally {
                try { os.close(); } catch (IOException ignored) {}
            }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }

    @AfterEach
    void tearDown() { server.stop(0); }

    static String frame(String id, String rec) {
        return "id: " + id + "\nevent: row_inserted\ndata: {\"topic\":\"collections.t.row_inserted\","
            + "\"eventType\":\"row_inserted\",\"payload\":{\"id\":\"" + rec + "\"},"
            + "\"occurredAt\":\"2026-10-27T10:20:38.969Z\",\"eventId\":\"" + id + "\"}\n\n";
    }

    static final String HEAD = "retry: 10\n:ok\n\n";

    Realtime realtime() {
        return K9o.createClient(K9o.Opts.builder().baseUrl(baseUrl).projectSlug("portal").build()).realtime();
    }

    Realtime.SubscribeOptions fast() {
        return new Realtime.SubscribeOptions().reconnectDelay(Duration.ofMillis(10));
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
    void yieldsFrameWithEventId() {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        try (var s = realtime().subscribe("collections.t.*", null, fast())) {
            var evs = take(s, 1);
            assertEquals("evt_A", evs.get(0).eventId());
            assertEquals("row_inserted", evs.get(0).eventType());
        }
        assertEquals("<none>", lastEventIds.get(0));
    }

    @Test
    void reconnectsWithLastEventIdAndDropsDuplicates() {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1") + frame("evt_B", "rec_2")));
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1") + frame("evt_B", "rec_2") + frame("evt_C", "rec_3")));
        try (var s = realtime().subscribe("collections.t.*", null, fast())) {
            var ids = take(s, 3).stream().map(Realtime.RealtimeEvent::eventId).toList();
            assertEquals(List.of("evt_A", "evt_B", "evt_C"), ids);
        }
        assertEquals("<none>", lastEventIds.get(0));
        assertEquals("evt_B", lastEventIds.get(1));
    }

    @Test
    void resyncCallsHandlerIsNotYieldedAndReconnectsFresh() {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")
            + "event: resync\ndata: {\"reason\":\"cursor-unknown\",\"action\":\"reread-source\"}\n\n"));
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        List<Realtime.Resync> calls = new ArrayList<>();
        var opts = fast().onResync(calls::add);
        try (var s = realtime().subscribe("collections.t.*", null, opts)) {
            var evs = take(s, 2);
            // the dedupe set was cleared, so evt_A is delivered again on the fresh connection
            assertEquals(List.of("evt_A", "evt_A"), evs.stream().map(Realtime.RealtimeEvent::eventId).toList());
        }
        assertEquals(1, calls.size());
        assertEquals("cursor-unknown", calls.get(0).reason());
        assertEquals("reread-source", calls.get(0).action());
        assertEquals("<none>", lastEventIds.get(1));
    }

    static final String HEAD_RESYNC =
        "event: resync\ndata: {\"reason\":\"cursor-evicted\",\"head\":7,\"action\":\"reread-source\"}\n\n";

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void openingResyncOnAFreshConnectionIsInformationalAndEventsFollowOnTheSameConnection() {
        script.add(Reply.sse(HEAD + HEAD_RESYNC + frame("evt_A", "rec_1")));
        List<Realtime.Resync> calls = new ArrayList<>();
        try (var s = realtime().subscribe("collections.t.*", null, fast().onResync(calls::add))) {
            var evs = take(s, 1);
            assertEquals("evt_A", evs.get(0).eventId());
        }
        assertTrue(calls.isEmpty(), "onResync is not called for the opening resync");
        assertEquals(1, hits.get(), "no reconnect");
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void resyncAfterAnEventIsStillARealResync() {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1") + HEAD_RESYNC));
        script.add(Reply.sse(HEAD + frame("evt_B", "rec_2")));
        List<Realtime.Resync> calls = new ArrayList<>();
        try (var s = realtime().subscribe("collections.t.*", null, fast().onResync(calls::add))) {
            assertEquals(List.of("evt_A", "evt_B"), take(s, 2).stream().map(Realtime.RealtimeEvent::eventId).toList());
        }
        assertEquals(1, calls.size());
        assertEquals("<none>", lastEventIds.get(1));
    }

    @Test
    @org.junit.jupiter.api.Timeout(15)
    void resyncOnAConnectionThatSentLastEventIdIsStillARealResync() {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        script.add(Reply.sse(HEAD + HEAD_RESYNC));
        script.add(Reply.sse(HEAD + frame("evt_B", "rec_2")));
        List<Realtime.Resync> calls = new ArrayList<>();
        try (var s = realtime().subscribe("collections.t.*", null, fast().onResync(calls::add))) {
            assertEquals(List.of("evt_A", "evt_B"), take(s, 2).stream().map(Realtime.RealtimeEvent::eventId).toList());
        }
        assertEquals("evt_A", lastEventIds.get(1));
        assertEquals(1, calls.size());
        assertEquals("<none>", lastEventIds.get(2));
    }

    @Test
    void heartbeatCommentsAreIgnored() {
        script.add(Reply.sse(HEAD + ":hb\n\n" + frame("evt_A", "rec_1") + ":hb\n\n"));
        try (var s = realtime().subscribe("collections.t.*", null, fast())) {
            assertEquals(1, take(s, 1).size());
        }
    }

    @Test
    void clientErrorIsThrownOnceAndServerErrorIsRetried() {
        script.add(Reply.code(403));
        try (var s = realtime().subscribe("collections.t.*", null, fast())) {
            KError e = assertThrows(KError.class, () -> s.iterator().hasNext());
            assertEquals(403, e.getStatus());
        }
        assertEquals(1, hits.get());

        hits.set(0);
        script.clear();
        script.add(Reply.code(503));
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        try (var s = realtime().subscribe("collections.t.*", null, fast())) {
            assertEquals(1, take(s, 1).size());
        }
        assertEquals(2, hits.get());
    }

    @Test
    void closeStopsReconnecting() throws Exception {
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        var s = realtime().subscribe("collections.t.*", null, fast());
        var it = s.iterator();
        assertTrue(it.hasNext());
        it.next();
        Thread t = new Thread(() -> {
            try { Thread.sleep(150); } catch (InterruptedException ignored) {}
            s.close();
        });
        t.start();
        assertFalse(it.hasNext()); // blocked reconnecting (all replays are duplicates) until closed
        t.join();
        int after = hits.get();
        Thread.sleep(200);
        assertEquals(after, hits.get());
    }

    @Test
    void idleConnectionIsAbandonedAndReconnected() {
        script.add(Reply.stalled());
        script.add(Reply.sse(HEAD + frame("evt_A", "rec_1")));
        var opts = fast().idleTimeout(Duration.ofMillis(200));
        long start = System.nanoTime();
        try (var s = realtime().subscribe("collections.t.*", null, opts)) {
            assertEquals(1, take(s, 1).size());
        }
        assertEquals(2, hits.get());
        assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2500);
    }
}
