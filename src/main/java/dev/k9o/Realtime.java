package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.io.BufferedReader;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Realtime subscriptions namespace — {@code client.realtime()}.
 *
 * <p>{@link #subscribe} returns an {@link EventStream}{@code <RealtimeEvent>} iterated via
 * {@code for (var event : stream)}. The transport is SSE over {@code GET /@<slug>/realtime/subscribe}.
 * The stream reconnects by itself when a connection ends (the gateway closes each one after at most 300 seconds),
 * resuming with a {@code Last-Event-ID} header and dropping events it already delivered, so iteration ends only
 * when you close the stream or a request fails with a client error. Delivery is at least once: dedupe by
 * {@code eventId} if you also store events. A {@code resync} frame means the position was lost: it is not an
 * event, {@link SubscribeOptions#onResync} is called, and the stream continues from a fresh connection. The one
 * exception is a {@code resync} that is the first data on a connection opened without {@code Last-Event-ID} or a start position: the
 * gateway sends it as an informational head marker before the events, so it is skipped and the same connection is
 * kept.
 */
public final class Realtime {

    private final HttpTransport.Config config;

    Realtime(HttpTransport.Config config) { this.config = config; }

    /** Typed builders for the 6 closed topic families. */
    public static final class Topic {
        private Topic() {}
        public static String collection(String collection) { return "collections." + collection + ".*"; }
        public static String workflowRuns() { return "studio.workflows.runs.*"; }
        public static String queryExecuted(String queryId) { return "studio.queries.executed." + queryId; }
        public static String dashboardRefresh(String dashboardId) { return "studio.dashboards.refresh." + dashboardId; }
        public static String functionInvocations(String functionId) { return "forge.functions.invocations." + functionId; }
        public static String auditOrg(String eventFamily) { return "audit.org." + eventFamily; }
    }

    /**
     * A single realtime event, as the gateway frames it (wire spelling): {@code topic, eventType, payload, occurredAt}.
     * {@code sequence} (a string on the wire) is set only on events read with {@link #replay}; live events have none.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RealtimeEvent(
        @JsonProperty("topic") String topic,
        @JsonProperty("eventType") String eventType,
        @JsonProperty("payload") Object payload,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("eventId") String eventId,
        @JsonProperty("sequence") String sequence
    ) {
        public RealtimeEvent(String topic, String eventType, Object payload, String occurredAt) {
            this(topic, eventType, payload, occurredAt, null, null);
        }

        public RealtimeEvent(String topic, String eventType, Object payload, String occurredAt, String eventId) {
            this(topic, eventType, payload, occurredAt, eventId, null);
        }
    }

    /** The {@code resync} control frame: the stream position is lost, re-read current state. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Resync(
        @JsonProperty("reason") String reason,
        @JsonProperty("action") String action
    ) {}

    /** Optional settings for {@link #subscribe(String, Map, SubscribeOptions)}. */
    public static final class SubscribeOptions {
        private Consumer<Resync> onResync;
        private Duration idleTimeout = Duration.ofSeconds(45);
        private Duration reconnectDelay = Duration.ofSeconds(1);
        /** Accept frames that are not {@code RealtimeEvent}s (rollup cells, audit-tail frames). */
        boolean lenient;
        /** Called after a {@code resync}: events to deliver before the stream continues live. */
        java.util.function.Function<Resync, List<RealtimeEvent>> catchUp;
        /** Keep the resume position and the delivered-id memory across a {@code resync} (the audit tail does). */
        boolean keepPositionOnResync;
        /**
         * After a {@code resync}: reconnect without {@code Last-Event-ID} first, then run {@code catchUp} while that
         * connection is open (its frames wait unread), so nothing published during the catch-up is lost.
         */
        boolean connectFirstOnResync;

        public SubscribeOptions() {}

        /**
         * Called when the gateway reports a lost position. Re-read current state through the data API inside it:
         * iteration waits until it returns. The stream then reconnects without {@code Last-Event-ID}.
         */
        public SubscribeOptions onResync(Consumer<Resync> handler) { this.onResync = handler; return this; }

        /** Reconnect when no bytes (frames, comments or heartbeats) arrive for this long. Default 45 seconds. */
        public SubscribeOptions idleTimeout(Duration d) { this.idleTimeout = d; return this; }

        /** Delay before a reconnect when the gateway gives no {@code retry:} hint. Default 1 second. */
        public SubscribeOptions reconnectDelay(Duration d) { this.reconnectDelay = d; return this; }
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT",
                "realtime operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /**
     * Subscribe over SSE. Iterate via {@code for (var event : stream)}; close with try-with-resources.
     * {@code filter} (nullable) narrows the topic and composes with row-level security.
     */
    public EventStream<RealtimeEvent> subscribe(String topic, Map<String, Object> filter) {
        return subscribe(topic, filter, null);
    }

    /**
     * Subscribe with options. The returned stream reconnects automatically (see the class description); it ends
     * when closed, or by throwing a {@link KError} for a client-error response.
     */
    public EventStream<RealtimeEvent> subscribe(String topic, Map<String, Object> filter, SubscribeOptions options) {
        String slug = requireProjectSlug();
        Map<String, String> q = new HashMap<>();
        q.put("topic", topic);
        if (filter != null) {
            try {
                q.put("filter", HttpTransport.JSON.writeValueAsString(filter));
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new KError("K9O_INVALID_FILTER", "failed to encode filter: " + e.getMessage());
            }
        }
        Feed feed = new Feed(config, UrlGrammar.projectPath(slug, "realtime/subscribe"), q, null,
            options == null ? new SubscribeOptions() : options);
        return new EventStream<>(feed, feed::close);
    }

    /**
     * One page of {@link #replay}: {@code events} in order, {@code hasMore} when more follow, {@code nextCursor} as the
     * gateway gave it (null when none) and {@code resync}, the gateway's {@code resync} hint (for example
     * {@code reread-source}, meaning there is nothing to replay for the topic: read current state again), or null.
     */
    public record ReplayPage(List<RealtimeEvent> events, String nextCursor, boolean hasMore, String resync) {
        public ReplayPage {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /**
     * Options of {@link #replay}. {@code fromSequence} and {@code untilSequence} (whole numbers, 0 or more) bound the
     * range; {@code limit} is 1 to 10,000. Null fields are not sent.
     */
    public record ReplayOptions(Long fromSequence, Long untilSequence, Integer limit) {
        public static ReplayOptions none() { return new ReplayOptions(null, null, null); }

        public static ReplayOptions fromSequence(long sequence) { return new ReplayOptions(sequence, null, null); }
    }

    /** Read a topic's recorded events with default options. */
    public Envelope<ReplayPage> replay(String topic) {
        return replay(topic, null);
    }

    /**
     * Read the events recorded on a realtime topic in order ({@code POST realtime/replay}): the catch-up of
     * {@link #subscribe}. {@code topic} is required (at most 200 characters; the topics of {@link Topic}). The answer
     * decodes to the same {@link RealtimeEvent} as the live stream, with {@code sequence} set. When the gateway keeps no
     * recorded events for a topic it answers no events and {@code resync} {@code reread-source}. Audit topics need the
     * audit log's access; {@link Audit#replay} is the audit form of this call.
     */
    public Envelope<ReplayPage> replay(String topic, ReplayOptions opts) {
        String slug = requireProjectSlug();
        if (topic == null || topic.isEmpty() || topic.length() > 200) {
            throw new KError("K9O_INVALID_FIELD_VALUE", "topic is required and at most 200 characters", Map.of("field", "topic"), null, null, null);
        }
        ReplayOptions o = opts == null ? ReplayOptions.none() : opts;
        if (o.fromSequence() != null && o.fromSequence() < 0 || o.untilSequence() != null && o.untilSequence() < 0) {
            throw new KError("K9O_INVALID_FIELD_VALUE", "sequences must be whole numbers, 0 or more", Map.of("field", "fromSequence"), null, null, null);
        }
        if (o.limit() != null && (o.limit() < 1 || o.limit() > 10_000)) {
            throw new KError("K9O_INVALID_FIELD_VALUE", "limit must be 1 to 10000", Map.of("field", "limit"), null, null, null);
        }
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("topic", topic);
        if (o.fromSequence() != null) body.put("fromSequence", o.fromSequence());
        if (o.untilSequence() != null) body.put("untilSequence", o.untilSequence());
        if (o.limit() != null) body.put("limit", o.limit());
        Envelope<com.fasterxml.jackson.databind.JsonNode> env = HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(slug, "realtime/replay"), body, null,
            new com.fasterxml.jackson.core.type.TypeReference<Envelope<com.fasterxml.jackson.databind.JsonNode>>() {});
        com.fasterxml.jackson.databind.JsonNode d = env.data();
        if (d == null || !d.path("events").isArray()) {
            throw new KError("K9O_INTERNAL_ERROR", "Unexpected replay answer: data.events is not a list");
        }
        List<RealtimeEvent> out = new java.util.ArrayList<>();
        for (com.fasterxml.jackson.databind.JsonNode n : d.get("events")) {
            if (!n.isObject()) throw new KError("K9O_INTERNAL_ERROR", "Unexpected replay answer: an event is not an object");
            RealtimeEvent ev;
            try {
                ev = HttpTransport.JSON.treeToValue(n, RealtimeEvent.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new KError("K9O_INTERNAL_ERROR", "Unexpected replay answer: an event could not be read");
            }
            // The gateway sends sequence as a string; accept a number too.
            if (ev.sequence() == null && n.path("sequence").isNumber()) {
                ev = new RealtimeEvent(ev.topic(), ev.eventType(), ev.payload(), ev.occurredAt(), ev.eventId(), n.get("sequence").asText());
            }
            out.add(ev);
        }
        com.fasterxml.jackson.databind.JsonNode nc = d.path("nextCursor");
        com.fasterxml.jackson.databind.JsonNode rs = d.path("resync");
        return new Envelope<>(new ReplayPage(out, nc.isNull() || nc.isMissingNode() ? null : nc.asText(),
            d.path("hasMore").asBoolean(false), rs.isTextual() ? rs.asText() : null), env.meta());
    }

    /**
     * Open the reconnecting change feed on another SSE path of the project. {@code firstQuery} is added to the query
     * of the first connection only.
     */
    static EventStream<RealtimeEvent> openFeed(HttpTransport.Config config, String path, Map<String, String> query,
                                               Map<String, String> firstQuery, SubscribeOptions options) {
        Feed feed = new Feed(config, path, query, firstQuery, options == null ? new SubscribeOptions() : options);
        return new EventStream<>(feed, feed::close);
    }

    private static final int DEDUPE_LIMIT = 1024;
    private static final long MAX_BACKOFF_MS = 30_000;

    /** Reconnecting, de-duplicating iterator over the change feed. */
    private static final class Feed implements Iterator<RealtimeEvent> {
        private final HttpTransport.Config config;
        private final String path;
        private final Map<String, String> query;
        private Map<String, String> firstQuery;
        private final SubscribeOptions opts;
        private final CountDownLatch closedLatch = new CountDownLatch(1);
        private final Map<String, Boolean> seen = new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > DEDUPE_LIMIT; }
        };
        private volatile boolean closed;
        private volatile InputStream current;
        private volatile long lastByteNanos;
        private volatile boolean idled;
        private ScheduledExecutorService watchdog;
        private BufferedReader reader;
        private String lastEventId;
        private long serverRetryMs = -1;
        private int failures;
        private boolean needDelay;
        private boolean sawFrame;
        private volatile boolean catchingUp;
        private boolean catchUpPending;
        private boolean connFresh;
        private boolean caughtUp;
        private Resync pendingResync;
        private int resyncStreak;
        private RealtimeEvent next;
        private final java.util.ArrayDeque<RealtimeEvent> pending = new java.util.ArrayDeque<>();

        Feed(HttpTransport.Config config, String path, Map<String, String> query,
             Map<String, String> firstQuery, SubscribeOptions opts) {
            this.config = config;
            this.path = path;
            this.query = query;
            this.firstQuery = firstQuery;
            this.opts = opts;
        }

        void close() {
            closed = true;
            closedLatch.countDown();
            dropConnection();
            ScheduledExecutorService w = watchdog;
            if (w != null) w.shutdownNow();
        }

        private void dropConnection() {
            InputStream is = current;
            current = null;
            reader = null;
            if (is != null) {
                try { is.close(); } catch (IOException ignored) {}
            }
        }

        @Override
        public boolean hasNext() {
            if (next != null) return true;
            if (closed) return false;
            next = advance();
            return next != null;
        }

        @Override
        public RealtimeEvent next() {
            if (!hasNext()) throw new NoSuchElementException();
            RealtimeEvent out = next;
            next = null;
            return out;
        }

        private long baseDelayMs() {
            return serverRetryMs >= 0 ? serverRetryMs : opts.reconnectDelay.toMillis();
        }

        private long delayMs() {
            if (failures <= 1) return baseDelayMs();
            long d = baseDelayMs();
            for (int i = 1; i < failures && d < MAX_BACKOFF_MS; i++) d *= 2;
            return Math.min(d, MAX_BACKOFF_MS);
        }

        private boolean pause() {
            try {
                return !closedLatch.await(delayMs(), TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                close();
                return false;
            }
        }

        private RealtimeEvent advance() {
            while (!closed) {
                if (!pending.isEmpty()) return pending.poll();
                if (reader == null) {
                    if (needDelay && !pause()) return null;
                    needDelay = true;
                    if (!connect()) continue;
                    if (catchUpPending) {
                        catchUpPending = false;
                        runCatchUp();
                        if (!pending.isEmpty()) continue;
                    }
                }
                RealtimeEvent ev = readUntilEvent();
                if (ev != null) return ev;
            }
            return null;
        }

        /** Open a connection; false when it failed in a retryable way. A client error is thrown. */
        private boolean connect() {
            Map<String, String> headers = new HashMap<>();
            if (lastEventId != null) headers.put("last-event-id", lastEventId);
            InputStream raw;
            Map<String, String> q = query;
            boolean startPosition = firstQuery != null && !firstQuery.isEmpty() && lastEventId == null;
            if (firstQuery != null && lastEventId == null) {
                q = new HashMap<>(query);
                q.putAll(firstQuery);
            }
            try {
                raw = HttpTransport.openSseBody(config, "GET", path, null, q, headers, opts.idleTimeout);
            } catch (KError e) {
                Integer st = e.getStatus();
                if (st != null && st < 500) {
                    close();
                    throw e;
                }
                failures++;
                return false;
            }
            if (closed) {
                try { raw.close(); } catch (IOException ignored) {}
                return false;
            }
            firstQuery = null;
            lastByteNanos = System.nanoTime();
            idled = false;
            sawFrame = false;
            // A connection sent with Last-Event-ID or with a start position (fromSequence) is not fresh: the gateway
            // reads both as a cursor, so a resync on it is a real lost position.
            connFresh = lastEventId == null && !startPosition;
            caughtUp = false;
            InputStream tracked = new FilterInputStream(raw) {
                @Override public int read() throws IOException {
                    int b = super.read();
                    if (b >= 0) lastByteNanos = System.nanoTime();
                    return b;
                }
                @Override public int read(byte[] buf, int off, int len) throws IOException {
                    int n = super.read(buf, off, len);
                    if (n > 0) lastByteNanos = System.nanoTime();
                    return n;
                }
            };
            current = tracked;
            reader = new BufferedReader(new InputStreamReader(tracked, StandardCharsets.UTF_8));
            long idleNanos = opts.idleTimeout.toNanos();
            long period = Math.max(5, Math.min(1000, opts.idleTimeout.toMillis() / 4));
            synchronized (this) {
                if (watchdog == null) {
                    watchdog = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "k9o-feed-watchdog");
                        t.setDaemon(true);
                        return t;
                    });
                }
            }
            InputStream guarded = tracked;
            watchdog.scheduleAtFixedRate(() -> {
                if (current != guarded) throw new RuntimeException("stop");
                if (catchingUp) {
                    lastByteNanos = System.nanoTime();
                    return;
                }
                if (System.nanoTime() - lastByteNanos > idleNanos) {
                    idled = true;
                    try { guarded.close(); } catch (IOException ignored) {}
                    throw new RuntimeException("stop");
                }
            }, period, period, TimeUnit.MILLISECONDS);
            return true;
        }

        /** Read frames from the open connection until an event is deliverable or the connection ends. */
        private RealtimeEvent readUntilEvent() {
            String id = null;
            String event = null;
            List<String> data = new ArrayList<>();
            try {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isEmpty()) {
                        if (!data.isEmpty() || event != null) {
                            String name = event;
                            String payload = String.join("\n", data);
                            String frameId = id;
                            id = null;
                            event = null;
                            data.clear();
                            if ("resync".equals(name)) {
                                handleResync(payload);
                                return null;
                            }
                            RealtimeEvent ev = decode(payload, frameId, name);
                            if (ev != null) return ev;
                        }
                        continue;
                    }
                    if (line.startsWith(":")) {
                        if (line.startsWith(":hb")) failures = 0;
                        continue;
                    }
                    int c = line.indexOf(':');
                    String field = c < 0 ? line : line.substring(0, c);
                    String value = c < 0 ? "" : line.substring(c + 1);
                    if (value.startsWith(" ")) value = value.substring(1);
                    switch (field) {
                        case "id" -> id = value;
                        case "event" -> event = value;
                        case "data" -> data.add(value);
                        case "retry" -> {
                            try { serverRetryMs = Long.parseLong(value.trim()); } catch (NumberFormatException ignored) {}
                        }
                        default -> {}
                    }
                }
                // The server ended the stream. A connection that never produced a frame counts as a failure.
                if (!sawFrame) failures++;
            } catch (IOException e) {
                if (!closed) failures++;
            }
            dropConnection();
            return null;
        }

        private RealtimeEvent decode(String payload, String frameId, String frameName) {
            RealtimeEvent ev;
            try {
                if (opts.lenient) {
                    ev = decodeLenient(payload, frameId, frameName);
                } else {
                    ev = HttpTransport.JSON.readValue(payload, RealtimeEvent.class);
                }
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                close();
                throw new KError("K9O_INTERNAL_ERROR", "failed to decode stream chunk: " + e.getMessage());
            }
            if (ev == null || ev.eventType() == null) return null;
            String eid = ev.eventId() != null ? ev.eventId() : frameId;
            sawFrame = true;
            failures = 0;
            resyncStreak = 0;
            if (eid == null) return ev;
            if (seen.containsKey(eid)) return null;
            seen.put(eid, Boolean.TRUE);
            // The SSE id is the resume cursor; the frame's eventId only de-duplicates (on a lenient feed they may differ).
            lastEventId = opts.lenient && frameId != null ? frameId : eid;
            return ev.eventId() != null ? ev
                : new RealtimeEvent(ev.topic(), ev.eventType(), ev.payload(), ev.occurredAt(), eid, ev.sequence());
        }

        /**
         * A frame that is a {@link RealtimeEvent} (it has an {@code eventType}) is read as one: the audit tail and the
         * rollup stream both send that shape. Any other object frame becomes an event named by the SSE {@code event:}
         * field, with the frame as payload.
         */
        private static RealtimeEvent decodeLenient(String payload, String frameId, String frameName)
            throws com.fasterxml.jackson.core.JsonProcessingException {
            com.fasterxml.jackson.databind.JsonNode n = HttpTransport.JSON.readTree(payload);
            if (n == null || !n.isObject()) return null;
            if (n.path("eventType").isTextual()) return HttpTransport.JSON.treeToValue(n, RealtimeEvent.class);
            Object body = HttpTransport.JSON.treeToValue(n, Object.class);
            String name = frameName != null ? frameName : "message";
            return new RealtimeEvent(null, name, body, n.path("occurredAt").asText(null), frameId);
        }

        /** Run the catch-up on the open fresh connection; its events go out before the connection's own frames. */
        private void runCatchUp() {
            List<RealtimeEvent> got;
            catchingUp = true;
            caughtUp = true;
            try {
                got = opts.catchUp.apply(pendingResync);
            } finally {
                lastByteNanos = System.nanoTime();
                catchingUp = false;
            }
            for (RealtimeEvent ev : got) {
                if (ev.eventId() != null && seen.put(ev.eventId(), Boolean.TRUE) != null) continue;
                pending.add(ev);
            }
            if (current == null && !closed) {
                // The connection ended while catching up: open a fresh one and catch up again (duplicates are dropped).
                catchUpPending = true;
            }
        }

        private void handleResync(String payload) {
            Resync r;
            try {
                r = HttpTransport.JSON.readValue(payload, Resync.class);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                r = new Resync(null, null);
            }
            if (opts.connectFirstOnResync && opts.catchUp != null) {
                if (connFresh && !sawFrame && current != null) {
                    // The head resync a fresh connection opens with is informational: keep reading the same stream.
                    return;
                }
                resyncStreak++;
                pendingResync = r;
                if (opts.onResync != null) opts.onResync.accept(r);
                dropConnection();
                lastEventId = null;
                // A second resync in a row, with no event between, backs off (the delay grows) instead of spinning.
                failures = resyncStreak >= 2 ? resyncStreak - 1 : 0;
                catchUpPending = resyncStreak == 1;
                return;
            }
            if (!opts.keepPositionOnResync && connFresh && !sawFrame && current != null) {
                // The head resync the gateway sends first on a connection opened without Last-Event-ID is
                // informational: events follow on the same connection. Do not reconnect or call onResync.
                return;
            }
            dropConnection();
            if (!opts.keepPositionOnResync) {
                lastEventId = null;
                seen.clear();
            }
            failures = 0;
            if (opts.onResync != null) opts.onResync.accept(r);
            if (opts.catchUp != null) {
                for (RealtimeEvent ev : opts.catchUp.apply(r)) {
                    if (ev.eventId() != null && seen.put(ev.eventId(), Boolean.TRUE) != null) continue;
                    pending.add(ev);
                }
            }
        }
    }
}
