package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * Project-plane audit log namespace — {@code client.audit()}.
 * Distinct from {@code client.org().auditLog()}.
 *
 * <p>{@link #query} reads the log; {@link #tail} follows it live; {@link #replay} reads it in order from a position
 * (the catch-up of a tail). The tail and the replay need the audit log's access: organization owner or admin, project
 * admin or project auditor (re-checked about every 60 seconds while a tail is open). Extensions cannot subscribe.
 */
public final class Audit {

    private final HttpTransport.Config config;

    Audit(HttpTransport.Config config) {
        this.config = config;
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "audit operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Audit event record. */
    public record AuditEvent(
        @JsonProperty("id") String id,
        @JsonProperty("type") String type,
        @JsonProperty("actorId") String actorId,
        @JsonProperty("targetKind") String targetKind,
        @JsonProperty("targetId") String targetId,
        @JsonProperty("payload") Map<String, Object> payload,
        @JsonProperty("occurredAt") String occurredAt,
        @JsonProperty("requestId") String requestId,
        @JsonProperty("traceId") String traceId
    ) {}

    /**
     * Optional audit-query filters (null fields are omitted); names are the gateway's query parameters.
     * {@code eventType} is the audit-event name (for example {@code data.write.access.insert}); {@code after} and
     * {@code before} are ISO 8601 instants.
     */
    public record QueryOpts(
        String eventType, String actorId, String after, String before, Integer limit, String cursor
    ) {
        public static QueryOpts none() {
            return new QueryOpts(null, null, null, null, null, null);
        }

        Map<String, String> toQuery() {
            Map<String, String> q = new HashMap<>();
            if (eventType != null) q.put("eventType", eventType);
            if (actorId != null) q.put("actorId", actorId);
            if (after != null) q.put("after", after);
            if (before != null) q.put("before", before);
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            return q;
        }
    }

    /** Query the audit log (reverse-chronological; filters AND-compose). */
    public Envelope<List<AuditEvent>> query(QueryOpts opts) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "audit-log"),
            null, (opts == null ? QueryOpts.none() : opts).toQuery(), new TypeReference<Envelope<List<AuditEvent>>>() {});
    }

    // ---- live tail and replay

    private static final Pattern AUDIT_ID = Pattern.compile("^evt_[0-9A-Za-z]{1,40}$");
    private static final String ALL_TOPIC = "audit.project.*";
    private static final String CHECKPOINT = "warden.checkpoint";
    private static final int DEFAULT_REPLAY_LIMIT = 1000;
    private static final int CATCH_UP_PAGE = 1000;
    private static final int CATCH_UP_MAX_PAGES = 50;

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    /**
     * What a live frame carries: the id of the audit event, its {@code type} (for example
     * {@code data.write.access.insert}), the kind of {@code actorType}, when it {@code occurredAt} (ISO 8601) and its
     * {@code outcome}, flattened from the frame's {@code payload}. {@code eventId} is the id of the frame itself (the
     * publisher's id, not the audit id); {@code sequence} is set only on events that came from a catch-up
     * {@link #replay}. Read the full row with {@link #query}.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record TailEvent(String auditEventId, String type, String actorType, String occurredAt, String outcome,
                            String eventId, String sequence) {
        public TailEvent(String auditEventId, String type, String actorType, String occurredAt, String outcome) {
            this(auditEventId, type, actorType, occurredAt, outcome, null, null);
        }
    }

    /**
     * An event of {@link #replay}: the five fields of a live frame (flattened from the event's {@code payload}) plus its
     * {@code sequence} in the log (a string on the wire) and the {@code topic} it was published on.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ReplayEvent(String auditEventId, String type, String actorType, String occurredAt, String outcome,
                              String sequence, String topic) {}

    /**
     * One page of {@link #replay}: {@code events} in order, {@code hasMore} when the log has more after them, and
     * {@code nextCursor} as the gateway gave it (null when none). To continue, pass the last event's
     * {@code auditEventId} as {@code afterAuditEventId}.
     */
    public record ReplayPage(List<ReplayEvent> events, String nextCursor, boolean hasMore) {
        public ReplayPage {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }

    /**
     * Options of {@link #replay}. {@code topic} defaults to {@code audit.project.*}. Pass {@code afterAuditEventId} (the
     * last {@code auditEventId} you received) or {@code fromSequence}; {@code untilSequence} and {@code limit} (1 to
     * 10,000; default 1,000) are optional.
     */
    public record ReplayOpts(String topic, String afterAuditEventId, Long fromSequence, Long untilSequence, Integer limit) {
        public static ReplayOpts after(String auditEventId) { return new ReplayOpts(null, auditEventId, null, null, null); }

        public static ReplayOpts fromSequence(long sequence) { return new ReplayOpts(null, null, sequence, null, null); }
    }

    /**
     * Read the audit log in order from a position ({@code POST realtime/replay}). Unlike the live tail it includes every
     * audit event, {@code warden.checkpoint} too. An {@code afterAuditEventId} that is not in the log answers 400
     * {@code K9O_INVALID_CURSOR}; {@code afterAuditEventId} wins over {@code fromSequence}. At most 1,000 events come
     * back per call unless {@code limit} (up to 10,000) says otherwise; while {@code hasMore} is true, ask again after the last event.
     */
    public Envelope<ReplayPage> replay(ReplayOpts opts) {
        String slug = requireProjectSlug();
        if (opts == null) throw invalid("opts is required", Map.of("field", "opts"));
        if ((opts.afterAuditEventId() == null) == (opts.fromSequence() == null)) {
            throw invalid("send exactly one of afterAuditEventId or fromSequence", Map.of("field", "afterAuditEventId"));
        }
        if (opts.afterAuditEventId() != null && !AUDIT_ID.matcher(opts.afterAuditEventId()).matches()) {
            throw invalid("afterAuditEventId must look like evt_ followed by letters and digits", Map.of("field", "afterAuditEventId"));
        }
        if (opts.fromSequence() != null && opts.fromSequence() < 0 || opts.untilSequence() != null && opts.untilSequence() < 0) {
            throw invalid("sequences must be whole numbers, 0 or more", Map.of("field", "fromSequence"));
        }
        if (opts.limit() != null && (opts.limit() < 1 || opts.limit() > 10_000)) {
            throw invalid("limit must be 1 to 10000", Map.of("field", "limit"));
        }
        int limit = opts.limit() == null ? DEFAULT_REPLAY_LIMIT : opts.limit();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("topic", opts.topic() == null ? ALL_TOPIC : opts.topic());
        if (opts.afterAuditEventId() != null) body.put("afterAuditEventId", opts.afterAuditEventId());
        if (opts.fromSequence() != null) body.put("fromSequence", opts.fromSequence());
        if (opts.untilSequence() != null) body.put("untilSequence", opts.untilSequence());
        body.put("limit", limit);
        Envelope<JsonNode> env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "realtime/replay"),
            body, null, new TypeReference<Envelope<JsonNode>>() {});
        JsonNode d = env.data();
        if (d == null || !d.path("events").isArray()) {
            throw new KError("K9O_INTERNAL_ERROR", "Unexpected replay answer: data.events is not a list");
        }
        List<ReplayEvent> out = new ArrayList<>();
        for (JsonNode n : d.get("events")) {
            if (!n.isObject()) throw new KError("K9O_INTERNAL_ERROR", "Unexpected replay answer: an event is not an object");
            JsonNode p = n.path("payload");
            out.add(new ReplayEvent(
                text(p, "auditEventId"),
                p.path("type").isTextual() ? p.get("type").asText() : text(n, "eventType"),
                text(p, "actorType"),
                p.path("occurredAt").isTextual() ? p.get("occurredAt").asText() : text(n, "occurredAt"),
                text(p, "outcome"),
                n.path("sequence").isNull() || n.path("sequence").isMissingNode() ? null : n.get("sequence").asText(),
                text(n, "topic")));
        }
        JsonNode nc = d.path("nextCursor");
        return new Envelope<>(new ReplayPage(out, nc.isNull() || nc.isMissingNode() ? null : nc.asText(),
            d.path("hasMore").asBoolean(false)), env.meta());
    }

    private static String text(JsonNode n, String field) {
        JsonNode v = n.path(field);
        return v.isTextual() ? v.asText() : null;
    }

    /**
     * Options of {@link #tail}. {@code types} narrows the tail to audit event types: none follows every type
     * ({@code audit.project.*}), one subscribes to that type's topic, several follow all and keep the listed types. The
     * change-feed options are those of {@link Realtime.SubscribeOptions}; cancel by closing the stream.
     */
    public static final class TailOptions {
        private List<String> types = List.of();
        private boolean replayOnResync = true;
        private final Realtime.SubscribeOptions feed = new Realtime.SubscribeOptions();

        public TailOptions() {
            feed.lenient = true;
        }

        /** Audit event types to follow (for example {@code "data.write.access.insert"}); empty or {@code null}: all. */
        public TailOptions types(List<String> v) { this.types = v == null ? List.of() : List.copyOf(v); return this; }

        /**
         * Called when the gateway says the position is lost ({@code action} {@code replay-ledger}). With
         * {@link #replayOnResync} (the default) the tail then catches up by itself; iteration waits until this returns.
         */
        public TailOptions onResync(Consumer<Realtime.Resync> handler) { feed.onResync(handler); return this; }

        /**
         * On a {@code replay-ledger} resync, reconnect fresh first, then read the missed events with
         * {@link Audit#replay} (after the last event delivered) and deliver them, then the connection's own frames. Default true; false leaves the catch-up to your
         * {@code onResync} handler. With no event delivered yet there is nothing to catch up from.
         */
        public TailOptions replayOnResync(boolean v) { this.replayOnResync = v; return this; }

        /** Reconnect when no bytes (frames, comments or heartbeats) arrive for this long. Default 45 seconds. */
        public TailOptions idleTimeout(Duration d) { feed.idleTimeout(d); return this; }

        /** Delay before a reconnect when the gateway gives no {@code retry:} hint. Default 1 second. */
        public TailOptions reconnectDelay(Duration d) { feed.reconnectDelay(d); return this; }
    }

    /** Follow every audit event of the project live, with default options. */
    public EventStream<TailEvent> tail() {
        return tail(null);
    }

    /**
     * Follow the project's audit log live ({@code GET realtime/subscribe?topic=audit.project.*}). Each {@link TailEvent}
     * carries the five fields of a frame; read the full row with {@link #query}. The stream is the change-feed reader of
     * {@code realtime().subscribe}: it reconnects with {@code Last-Event-ID}, drops events it already delivered, ignores
     * heartbeats, reconnects after the idle timeout and ends only when you close it or a request fails with a client
     * error. Delivery is at least once: dedupe by {@code auditEventId} if you also store events.
     *
     * <p>When a position cannot be resumed the gateway sends {@code resync} with the action {@code replay-ledger}: the
     * tail then reads the missed events through {@link #replay} (after the last {@code auditEventId} it delivered) and
     * continues live; see {@link TailOptions#replayOnResync}. {@code warden.checkpoint} events are not in the live tail
     * (the log and the replay carry them). Who: organization owner or admin, project admin or auditor; 403
     * {@code K9O_RBAC_DENY} otherwise.
     */
    public EventStream<TailEvent> tail(TailOptions options) {
        String slug = requireProjectSlug();
        TailOptions o = options == null ? new TailOptions() : options;
        Set<String> types = new HashSet<>();
        for (String t : o.types) {
            if (t == null || t.isBlank() || t.contains("*") || t.chars().anyMatch(Character::isWhitespace)) {
                throw invalid("types must be audit event types without spaces or wildcards", Map.of("field", "types"));
            }
            types.add(t);
        }
        String topic = o.types.size() == 1 ? "audit.project." + o.types.get(0) : ALL_TOPIC;
        AtomicReference<String> last = new AtomicReference<>();
        Realtime.SubscribeOptions feedOpts = o.feed;
        feedOpts.keepPositionOnResync = true;
        feedOpts.connectFirstOnResync = o.replayOnResync;
        if (o.replayOnResync) {
            feedOpts.catchUp = r -> "replay-ledger".equals(r.action()) ? catchUp(topic, last.get(), types) : List.of();
        }
        Map<String, String> q = new HashMap<>();
        q.put("topic", topic);
        EventStream<Realtime.RealtimeEvent> raw =
            Realtime.openFeed(config, UrlGrammar.projectPath(slug, "realtime/subscribe"), q, null, feedOpts);
        Map<String, Boolean> delivered = new LinkedHashMap<>(16, 0.75f, false) {
            @Override protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) { return size() > 4096; }
        };
        return raw.mapNonNull(ev -> {
            TailEvent e = toTail(ev);
            if (e == null) return null;
            if (delivered.put(e.auditEventId(), Boolean.TRUE) != null) return null;
            last.set(e.auditEventId());
            if (CHECKPOINT.equals(e.type())) return null;
            if (!types.isEmpty() && !types.contains(e.type())) return null;
            return e;
        });
    }

    /** The five fields flattened from the frame's {@code payload}; null when the frame carries no audit id. */
    @SuppressWarnings("unchecked")
    private static TailEvent toTail(Realtime.RealtimeEvent ev) {
        Map<String, Object> p = ev.payload() instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        if (!(p.get("auditEventId") instanceof String id)) return null;
        return new TailEvent(id,
            p.get("type") instanceof String s ? s : ev.eventType(),
            p.get("actorType") instanceof String s ? s : null,
            p.get("occurredAt") instanceof String s ? s : ev.occurredAt(),
            p.get("outcome") instanceof String s ? s : null,
            ev.eventId(),
            p.get("sequence") instanceof String s ? s : null);
    }

    /**
     * The events after {@code lastId}, as feed events, page by page until the log has nothing more. The replay has no
     * cursor parameter, so each page continues after its last event.
     */
    private List<Realtime.RealtimeEvent> catchUp(String topic, String lastId, Set<String> types) {
        List<Realtime.RealtimeEvent> out = new ArrayList<>();
        if (lastId == null) return out;
        String after = lastId;
        for (int page = 0; page < CATCH_UP_MAX_PAGES; page++) {
            String before = after;
            ReplayPage got = replay(new ReplayOpts(topic, after, null, null, CATCH_UP_PAGE)).data();
            for (ReplayEvent e : got.events()) {
                if (e.auditEventId() == null) continue;
                after = e.auditEventId();
                if (CHECKPOINT.equals(e.type())) continue;
                if (!types.isEmpty() && !types.contains(e.type())) continue;
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("auditEventId", e.auditEventId());
                payload.put("type", e.type());
                payload.put("actorType", e.actorType());
                payload.put("occurredAt", e.occurredAt());
                payload.put("outcome", e.outcome());
                payload.put("sequence", e.sequence());
                out.add(new Realtime.RealtimeEvent(topic, e.type(), payload, e.occurredAt(), null));
            }
            if (!got.hasMore() || got.events().isEmpty() || after.equals(before)) break;
        }
        return out;
    }
}
