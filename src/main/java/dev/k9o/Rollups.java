package dev.k9o;


import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Rollups across the project: {@code client.rollups()}. A rollup folds a stream into a dataset over fixed windows
 * (create one with {@code analytics().rollups().create}). Here you list every rollup you can read, read its cells for a
 * time range and follow its closed cells live.
 *
 * <p>Wire (project plane): {@code list} {@code GET /@<slug>/rollups}; {@code read} {@code GET
 * /@<slug>/rollups/<rollup>/read}; {@code subscribe} {@code GET /@<slug>/rollups/<rollup>/subscribe} (SSE). Reading needs
 * {@code datasets:read} on the rollup's dataset and {@code datasets:query}. Project service tokens and extension
 * tokens cannot call these routes.
 */
public final class Rollups {

    private final HttpTransport.Config config;

    Rollups(HttpTransport.Config config) { this.config = config; }

    private String path(String tail) {
        return UrlGrammar.projectPath(Analytics.project(config), "rollups" + tail);
    }

    /** Every rollup in the project whose dataset the caller can read, with its read and subscribe endpoints. */
    public Envelope<List<Map<String, Object>>> list() {
        return Analytics.asList(HttpTransport.request(config, "GET", path(""), null, null, Analytics.NODE), "rollups list");
    }

    /**
     * Options of {@link #read}: {@code from} and {@code to} (ISO 8601 instants; the gateway default is the last 24
     * hours, at most 50,000 cells) and {@code dims}, a projection onto some of the dimensions (only {@code count},
     * {@code sum}, {@code min} and {@code max} measures combine). All fields may be {@code null}.
     */
    public record ReadOptions(String from, String to, List<String> dims) {
        public static ReadOptions none() { return new ReadOptions(null, null, null); }
    }

    /**
     * The rollup's cells for a time range. A cell below the cohort threshold comes back as
     * {@code {status: "suppressed", reason: "cohort_below_k"}}; {@code coverage} reports the range, the last fold and
     * whether it is complete.
     */
    public Envelope<Object> read(String rollupSlug, ReadOptions options) {
        String seg = Analytics.segment(rollupSlug, "rollupSlug");
        Map<String, String> q = new HashMap<>();
        if (options != null) {
            if (options.from() != null) q.put("from", options.from());
            if (options.to() != null) q.put("to", options.to());
            if (options.dims() != null && !options.dims().isEmpty()) q.put("dims", String.join(",", options.dims()));
        }
        return HttpTransport.request(config, "GET", path("/" + seg + "/read"), null, q.isEmpty() ? null : q, Analytics.ANY);
    }

    /** As {@link #read(String, ReadOptions)} with the default range. */
    public Envelope<Object> read(String rollupSlug) {
        return read(rollupSlug, null);
    }

    /**
     * Options of {@link #subscribe}: the change-feed options of {@link Realtime.SubscribeOptions} ({@code onResync},
     * {@code idleTimeout}, {@code reconnectDelay}) plus {@code fromSequence}, which starts from a known feed position
     * and is used on the first connection only.
     */
    public static final class SubscribeOptions {
        private Long fromSequence;
        private final Realtime.SubscribeOptions feed = new Realtime.SubscribeOptions();

        public SubscribeOptions() {
            feed.lenient = true;
        }

        public SubscribeOptions fromSequence(long v) { this.fromSequence = v; return this; }

        /** Called when the gateway says the position is lost ({@code action} {@code reread-source}); re-read the cells with {@link Rollups#read} inside it. */
        public SubscribeOptions onResync(Consumer<Realtime.Resync> handler) { feed.onResync(handler); return this; }

        /** Reconnect when no bytes arrive for this long. Default 45 seconds. */
        public SubscribeOptions idleTimeout(Duration d) { feed.idleTimeout(d); return this; }

        /** Delay before a reconnect when the gateway gives no {@code retry:} hint. Default 1 second. */
        public SubscribeOptions reconnectDelay(Duration d) { feed.reconnectDelay(d); return this; }
    }

    /**
     * One cell of the rollup stream: {@code cellKey}, the window {@code bucket}, the {@code dims} values, {@code isFinal}
     * (the window is closed) and the {@code measures}. A cell below the cohort threshold arrives with {@code status}
     * {@code "suppressed"} and {@code reason} {@code "cohort_below_k"} (see {@link #suppressed()}); it carries no
     * measures. {@code eventId} is the id of the frame (it de-duplicates a resume) and {@code occurredAt} its time.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Cell(
        @JsonProperty("cellKey") String cellKey,
        @JsonProperty("bucket") String bucket,
        @JsonProperty("dims") Map<String, Object> dims,
        @JsonProperty("final") Boolean isFinal,
        @JsonProperty("measures") Map<String, Object> measures,
        @JsonProperty("status") String status,
        @JsonProperty("reason") String reason,
        String eventId,
        String occurredAt
    ) {
        /** True when the cell is held back because its cohort is below the threshold; read {@link #reason()}. */
        public boolean suppressed() { return "suppressed".equals(status); }
    }

    /** Follow a rollup's closed cells live, with default options. */
    public EventStream<Cell> subscribe(String rollupSlug) {
        return subscribe(rollupSlug, null);
    }

    /**
     * Follow a rollup's cells live over server-sent events (event name {@code cell}); each iteration yields one
     * {@link Cell}. It is the change-feed reader of {@code realtime().subscribe}: the SSE {@code id} is the resume
     * cursor ({@code Last-Event-ID}; the first connection may start at {@code fromSequence}), a resume re-sends the last
     * few seconds, so cells already delivered are dropped by their {@code eventId}, and a connection ends after at most
     * 300 seconds and is reopened. A {@code resync} frame with the action {@code reread-source} reaches
     * {@code onResync}: re-read the cells with {@link #read} inside it. Heartbeats are not events. A cell below the
     * cohort threshold arrives as a suppressed cell with its dimension values removed. At most a fixed number of streams
     * may be open per caller (429 {@code K9O_SUBSCRIPTION_LIMIT_EXCEEDED}). Close the stream to stop.
     */
    public EventStream<Cell> subscribe(String rollupSlug, SubscribeOptions options) {
        String where = path("/" + Analytics.segment(rollupSlug, "rollupSlug") + "/subscribe");
        SubscribeOptions o = options == null ? new SubscribeOptions() : options;
        Map<String, String> first = null;
        if (o.fromSequence != null) {
            if (o.fromSequence < 0) {
                throw Analytics.invalid("fromSequence must be a whole number, 0 or more", Map.of("field", "fromSequence"));
            }
            first = new HashMap<>();
            first.put("fromSequence", Long.toString(o.fromSequence));
        }
        return Realtime.openFeed(config, where, new HashMap<>(), first, o.feed).mapNonNull(ev -> {
            if (!(ev.payload() instanceof Map<?, ?> m)) return null;
            Map<String, Object> p = new HashMap<>();
            m.forEach((k, v) -> p.put(String.valueOf(k), v));
            p.put("eventId", ev.eventId());
            p.put("occurredAt", ev.occurredAt());
            return HttpTransport.JSON.convertValue(p, Cell.class);
        });
    }
}
