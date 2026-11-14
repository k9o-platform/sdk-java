package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Metadata: {@code client.metadata()}. Metadata is a set of columns you declare on a collection (or on another data
 * surface) so that they can be filtered, searched and followed live through one API, with the same access rules as
 * the data itself.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code filter(input)}: {@code POST /@<slug>/metadata/query}</li>
 * <li>{@code set(collection, id, metadata)}: {@code PATCH /@<slug>/metadata/collection/<collection>/<id>}</li>
 * <li>{@code search(input)}: {@code POST /@<slug>/metadata/search}</li>
 * <li>{@code vectors().nearest(input)}: {@code POST /@<slug>/metadata/vectors/nearest}</li>
 * <li>{@code subscribe(surface, options)}: {@code GET /@<slug>/metadata/subscribe?surface=} (SSE)</li>
 * <li>{@code index().get(collection)}: {@code GET /@<slug>/metadata/index/<collection>}</li>
 * <li>{@code index().declare(collection, columns)}: {@code PUT /@<slug>/metadata/index/<collection>}</li>
 * </ul>
 *
 * <p>Columns are declared first ({@code index().declare}); {@code set} writes declared columns only, and
 * {@code filter}, {@code search} and {@code vectors().nearest} read declared columns only. Column names given to
 * {@code filter} ({@code where[].field}), {@code search} ({@code field}) and {@code nearest} ({@code column}) are sent
 * as {@code metadata.<name>}; the prefix is added when you leave it out. Rows come back with bare column names under
 * {@code metadata}. The next page's cursor is {@code meta().cursor().next()} of the envelope.
 *
 * <p>Errors arrive as {@link KError} with the gateway's code. The ones to expect:
 * <ul>
 * <li>400 {@code K9O_UNKNOWN_SURFACE}: the surface is not {@code collection:<name>}, {@code telemetry:<event>} or {@code storage:<bucket>}.</li>
 * <li>400 {@code K9O_METADATA_FIELD_NOT_INDEXED}: the column is not declared.</li>
 * <li>400 {@code K9O_ENCRYPTED_COLUMN_OPERATION_UNSUPPORTED}: an encrypted column supports only {@code eq} (not null) and {@code in}, no search or nearest.</li>
 * <li>400 {@code K9O_INVALID_FIELD_VALUE}, {@code K9O_INVALID_CURSOR}: a bad body field or cursor.</li>
 * <li>403 {@code K9O_ACCESS_DENIED}, {@code K9O_EXTENSION_PERMISSION_DENIED}: no access to the surface.</li>
 * <li>404 {@code K9O_COLLECTION_NOT_FOUND} (declare on a missing collection), {@code K9O_NOT_FOUND} (set on a missing record).</li>
 * <li>413 {@code K9O_QUERY_RESULT_TOO_LARGE}: more than 10000 rows across the surfaces.</li>
 * <li>501 {@code K9O_NOT_IMPLEMENTED}: {@code storage:} surfaces are accepted by the SDK, and the gateway answers 501 for them.</li>
 * </ul>
 */
public final class Metadata {

    private static final Set<String> OPS = Set.of(
        "eq", "neq", "gt", "gte", "lt", "lte", "contains", "startsWith", "endsWith", "in", "notIn", "and", "or", "not");
    private static final String OPS_TEXT =
        "eq, neq, gt, gte, lt, lte, contains, startsWith, endsWith, in, notIn, and, or, not";
    private static final Set<String> METRICS = Set.of("cosine", "euclidean", "dot");
    private static final Set<String> SLICES = Set.of("collection", "telemetry", "storage");
    private static final Set<String> TELEMETRY_FIELDS = Set.of("createdAt", "actorId");
    private static final Pattern SCOPE = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._:-]{0,127}$");
    private static final Pattern IDENT = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");
    private static final String PREFIX = "metadata.";
    private static final int MAX_COLUMNS = 64;
    private static final int MAX_LIMIT = 1000;

    private final HttpTransport.Config config;
    private final MetadataVectors vectors;
    private final MetadataIndex index;

    Metadata(HttpTransport.Config config) {
        this.config = config;
        this.vectors = new MetadataVectors();
        this.index = new MetadataIndex();
    }

    /**
     * One condition. On a {@code collection:} surface {@code field} is a declared column ({@code priority} or
     * {@code metadata.priority}; the prefix is added when missing) and each field may appear once. On a
     * {@code telemetry:} surface the fields are {@code createdAt} (with {@code gt}, {@code gte}, {@code lt},
     * {@code lte}) and {@code actorId} (with {@code eq}). {@code op} is one of {@code eq neq gt gte lt lte contains
     * startsWith endsWith in notIn}; {@code and}, {@code or} and {@code not} are part of the grammar, but the gateway
     * refuses them today. {@code value} is always sent ({@code null} with {@code eq} or {@code neq} means is null or
     * is not null).
     */
    public record Where(String field, String op, Object value) {}

    /**
     * One result row. A {@code collection:} row has {@code recordId}; a {@code telemetry:} row has {@code eventId}
     * (the other is {@code null}). {@code metadata} holds the declared columns that are present on the row, under
     * their bare names.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Row(
        @JsonProperty("surface") String surface,
        @JsonProperty("scope") String scope,
        @JsonProperty("recordId") String recordId,
        @JsonProperty("eventId") String eventId,
        @JsonProperty("metadata") Map<String, Object> metadata
    ) {}

    /**
     * Input of {@link #filter}. {@code surfaces}: one or more of {@code collection:<name>},
     * {@code telemetry:<event-prefix>} or {@code storage:<bucket>}. {@code where}: conditions applied to every
     * surface (may be {@code null}). {@code limit}: rows per surface, 1 to 1000 (the gateway defaults to 100).
     * {@code cursor}: the cursor of the previous page, allowed with exactly one surface. Fields left {@code null}
     * are not sent.
     */
    public record FilterInput(List<String> surfaces, List<Where> where, Integer limit, String cursor) {
        public static FilterInput of(String... surfaces) {
            return new FilterInput(List.of(surfaces), null, null, null);
        }
    }

    /**
     * Input of {@link #search}. {@code surface}: a {@code collection:<name>} surface. {@code field}: the declared
     * text column ({@code body} or {@code metadata.body}). {@code q}: the words to look for; matching is by word
     * stem, so "finance" does not match "financial". {@code limit}: 1 to 1000 (the gateway defaults to 50), or
     * {@code null}.
     */
    public record SearchInput(String surface, String field, String q, Integer limit) {}

    /**
     * Input of {@link MetadataVectors#nearest}. {@code surface}: a {@code collection:<name>} surface.
     * {@code column}: the declared vector column ({@code embedding} or {@code metadata.embedding}).
     * {@code embedding}: a non-empty list of finite numbers. {@code k}: 1 to 1000 (the gateway defaults to 10), or
     * {@code null}. {@code metric}: {@code cosine} (default), {@code euclidean} or {@code dot}, or {@code null}.
     * {@code where}: conditions rows must also meet, or {@code null}.
     */
    public record NearestInput(String surface, String column, List<? extends Number> embedding, Integer k,
                               String metric, List<Where> where) {
        public static NearestInput of(String surface, String column, List<? extends Number> embedding) {
            return new NearestInput(surface, column, embedding, null, null, null);
        }
    }

    /** A column to declare. {@code indexed} defaults to false when {@code null}. */
    public record Column(String column, Boolean indexed) {
        public static Column of(String column) {
            return new Column(column, null);
        }
    }

    /** A declared column as the gateway reports it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DeclaredColumn(@JsonProperty("column") String column, @JsonProperty("indexed") boolean indexed) {}

    /** A collection's declaration: its declared columns, ordered by name. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Declaration(
        @JsonProperty("collection") String collection,
        @JsonProperty("columns") List<DeclaredColumn> columns
    ) {}

    /**
     * Options of {@link #subscribe(String, SubscribeOptions)}: the change-feed options of
     * {@link Realtime.SubscribeOptions} ({@code onResync}, {@code idleTimeout}, {@code reconnectDelay}) plus
     * {@code fromSequence}, which starts from a known feed position instead of from now. It is used on the first
     * connection only: after the stream has resumed by itself or been resynced it is not sent again.
     */
    public static final class SubscribeOptions {
        private Long fromSequence;
        private Realtime.SubscribeOptions feed = new Realtime.SubscribeOptions();

        public SubscribeOptions() {}

        public SubscribeOptions fromSequence(long v) { this.fromSequence = v; return this; }

        /** Called when the gateway reports a lost position; re-read the records through the data API inside it. */
        public SubscribeOptions onResync(java.util.function.Consumer<Realtime.Resync> handler) { feed.onResync(handler); return this; }

        /** Reconnect when no bytes arrive for this long. Default 45 seconds. */
        public SubscribeOptions idleTimeout(java.time.Duration d) { feed.idleTimeout(d); return this; }

        /** Delay before a reconnect when the gateway gives no {@code retry:} hint. Default 1 second. */
        public SubscribeOptions reconnectDelay(java.time.Duration d) { feed.reconnectDelay(d); return this; }
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private static Map<String, Object> ctx(String key, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(key, String.valueOf(value));
        return m;
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "metadata operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "metadata/" + tail);
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String sliceOf(String surface) {
        return surface.substring(0, surface.indexOf(':'));
    }

    private static String checkSurface(String surface, String name) {
        if (surface == null) throw invalid(name + " must be a string such as collection:posts", ctx("surface", null));
        int at = surface.indexOf(':');
        String slice = at < 0 ? "" : surface.substring(0, at);
        String scope = at < 0 ? "" : surface.substring(at + 1);
        if (!SLICES.contains(slice) || !SCOPE.matcher(scope).matches()
            || (slice.equals("collection") && !IDENT.matcher(scope).matches())) {
            throw new KError("K9O_UNKNOWN_SURFACE",
                name + " \"" + surface + "\" is not a surface: use collection:<name>, telemetry:<event> or storage:<bucket>",
                ctx("surface", surface), null, null, null);
        }
        return surface;
    }

    private static String checkCollectionSurface(String surface, String name) {
        String s = checkSurface(surface, name);
        if (!sliceOf(s).equals("collection")) {
            throw invalid(name + " must be a collection surface (collection:<name>)", ctx("surface", s));
        }
        return s;
    }

    private static void checkLimit(Integer value, String name) {
        if (value != null && (value < 1 || value > MAX_LIMIT)) {
            throw invalid(name + " must be a whole number from 1 to " + MAX_LIMIT, ctx(name, value));
        }
    }

    private static String prefixed(String name, String label) {
        if (name == null || name.isEmpty() || name.equals(PREFIX)) {
            throw invalid(label + " must be a column name", ctx(label, name));
        }
        return name.startsWith(PREFIX) ? name : PREFIX + name;
    }

    private static List<Map<String, Object>> wireWhere(List<Where> where, boolean telemetry) {
        if (where == null) return null;
        List<Map<String, Object>> out = new ArrayList<>();
        for (int i = 0; i < where.size(); i++) {
            Where c = where.get(i);
            if (c == null) throw invalid("where[" + i + "] must be an object", Map.of());
            if (c.op() == null || !OPS.contains(c.op())) {
                throw invalid("where[" + i + "].op must be one of: " + OPS_TEXT, ctx("op", c.op()));
            }
            boolean bare = telemetry && c.field() != null && TELEMETRY_FIELDS.contains(c.field());
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("field", bare ? c.field() : prefixed(c.field(), "where[" + i + "].field"));
            m.put("op", c.op());
            m.put("value", c.value());
            out.add(m);
        }
        return out;
    }

    private static String collectionName(String collection) {
        if (collection == null || !IDENT.matcher(collection).matches()) {
            throw invalid("collection must be a collection name", ctx("collection", collection));
        }
        return collection;
    }

    private static final TypeReference<Envelope<List<Row>>> ROWS = new TypeReference<>() {};

    /**
     * Rows of one or more surfaces whose declared columns meet {@code where}, in request order. Pages with
     * {@code cursor} when exactly one surface is named ({@code meta().cursor().next()}, {@code null} on the last
     * page). More than 10000 rows in all answers 413 {@code K9O_QUERY_RESULT_TOO_LARGE}; a {@code storage:} surface
     * answers 501 {@code K9O_NOT_IMPLEMENTED}.
     */
    public Envelope<List<Row>> filter(FilterInput input) {
        String where = path("query");
        if (input == null) throw invalid("input must be an object", Map.of());
        if (input.surfaces() == null || input.surfaces().isEmpty()) throw invalid("surfaces must be a non-empty array", Map.of());
        List<String> surfaces = new ArrayList<>();
        boolean telemetry = false;
        for (int i = 0; i < input.surfaces().size(); i++) {
            String s = checkSurface(input.surfaces().get(i), "surfaces[" + i + "]");
            surfaces.add(s);
            if (sliceOf(s).equals("telemetry")) telemetry = true;
        }
        checkLimit(input.limit(), "limit");
        if (input.cursor() != null) {
            if (input.cursor().isEmpty()) throw invalid("cursor must be the cursor of a previous page", Map.of());
            if (surfaces.size() != 1) throw invalid("cursor is allowed only with exactly one surface", ctx("field", "cursor"));
        }
        List<Map<String, Object>> wire = wireWhere(input.where(), telemetry);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surfaces", surfaces);
        if (wire != null) body.put("where", wire);
        if (input.limit() != null) body.put("limit", input.limit());
        if (input.cursor() != null) body.put("cursor", input.cursor());
        return HttpTransport.request(config, "POST", where, body, null, ROWS);
    }

    /**
     * Write declared columns of one record: {@code set("posts", "p2", Map.of("title", "New", "priority", 9))}. Keys
     * are bare column names (a leading {@code metadata.} is dropped); a column that is not declared answers 400
     * {@code K9O_METADATA_FIELD_NOT_INDEXED}. Returns the whole updated record, including columns that are not
     * declared. The write runs through the record API's rules, so a write that needs approval is queued as it is there.
     */
    public Envelope<Map<String, Object>> set(String collection, String id, Map<String, ?> metadata) {
        String name = collectionName(collection);
        if (id == null || id.isEmpty()) throw invalid("id must be a record id", ctx("id", id));
        if (metadata == null || metadata.isEmpty()) throw invalid("metadata must be an object with at least one column", Map.of());
        Map<String, Object> bare = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : metadata.entrySet()) {
            String key = e.getKey();
            bare.put(key.startsWith(PREFIX) ? key.substring(PREFIX.length()) : key, e.getValue());
        }
        return HttpTransport.request(config, "PATCH", path("collection/" + enc(name) + "/" + enc(id)),
            Map.of("metadata", bare), null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /**
     * Full-text search over one declared text column of a collection. Returns the first page only (there is no
     * cursor input), with no rank.
     */
    public Envelope<List<Row>> search(SearchInput input) {
        String where = path("search");
        if (input == null) throw invalid("input must be an object", Map.of());
        String surface = checkCollectionSurface(input.surface(), "surface");
        String field = prefixed(input.field(), "field");
        if (input.q() == null || input.q().isEmpty()) throw invalid("q must be a non-empty string", Map.of());
        checkLimit(input.limit(), "limit");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("surface", surface);
        body.put("field", field);
        body.put("q", input.q());
        if (input.limit() != null) body.put("limit", input.limit());
        return HttpTransport.request(config, "POST", where, body, null, ROWS);
    }

    /** Nearest-neighbour search over a declared vector column. */
    public MetadataVectors vectors() { return vectors; }

    /** The column declaration of a collection. */
    public MetadataIndex index() { return index; }

    /** {@code metadata().vectors()}. */
    public final class MetadataVectors {
        private MetadataVectors() {}

        /**
         * The rows whose declared vector column is nearest to {@code embedding}, closest first. Rows carry no score.
         * At most 50 rows come back whatever {@code k} is, until the gateway forwards a larger page size.
         */
        public Envelope<List<Row>> nearest(NearestInput input) {
            String where = path("vectors/nearest");
            if (input == null) throw invalid("input must be an object", Map.of());
            String surface = checkCollectionSurface(input.surface(), "surface");
            String column = prefixed(input.column(), "column");
            List<? extends Number> emb = input.embedding();
            if (emb == null || emb.isEmpty()) throw invalid("embedding must be a non-empty array of numbers", Map.of());
            for (Number n : emb) {
                if (n == null || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
                    throw invalid("embedding must contain only finite numbers", Map.of());
                }
            }
            checkLimit(input.k(), "k");
            if (input.metric() != null && !METRICS.contains(input.metric())) {
                throw invalid("metric must be one of: cosine, euclidean, dot", ctx("metric", input.metric()));
            }
            List<Map<String, Object>> wire = wireWhere(input.where(), false);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("surface", surface);
            body.put("column", column);
            body.put("embedding", emb);
            if (input.k() != null) body.put("k", input.k());
            if (input.metric() != null) body.put("metric", input.metric());
            if (wire != null) body.put("where", wire);
            return HttpTransport.request(config, "POST", where, body, null, ROWS);
        }
    }

    /** {@code metadata().index()}. */
    public final class MetadataIndex {
        private MetadataIndex() {}

        /** The columns declared on a collection, ordered by name. */
        public Envelope<Declaration> get(String collection) {
            String name = collectionName(collection);
            return HttpTransport.request(config, "GET", path("index/" + enc(name)), null, null,
                new TypeReference<Envelope<Declaration>>() {});
        }

        /**
         * Declare the columns of a collection that metadata may use. The columns must already exist on the
         * collection. The list replaces the previous declaration (an empty list clears it); at most 64 columns, no
         * {@code id}, no duplicates. Needs the project admin role and the migrations permission.
         */
        public Envelope<Declaration> declare(String collection, List<Column> columns) {
            String name = collectionName(collection);
            if (columns == null) throw invalid("columns must be an array of { column, indexed? }", Map.of());
            if (columns.size() > MAX_COLUMNS) {
                throw invalid("at most " + MAX_COLUMNS + " columns can be declared", ctx("count", columns.size()));
            }
            Set<String> seen = new HashSet<>();
            List<Map<String, Object>> wire = new ArrayList<>();
            for (int i = 0; i < columns.size(); i++) {
                Column c = columns.get(i);
                if (c == null) throw invalid("columns[" + i + "] must be an object", Map.of());
                if (c.column() == null || !IDENT.matcher(c.column()).matches() || c.column().equals("id")) {
                    throw invalid("columns[" + i + "].column must be a column name other than id", ctx("column", c.column()));
                }
                if (!seen.add(c.column())) {
                    throw invalid("column \"" + c.column() + "\" is listed twice", ctx("column", c.column()));
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("column", c.column());
                if (c.indexed() != null) m.put("indexed", c.indexed());
                wire.add(m);
            }
            return HttpTransport.request(config, "PUT", path("index/" + enc(name)), Map.of("columns", wire), null,
                new TypeReference<Envelope<Declaration>>() {});
        }
    }

    /** Follow a {@code collection:<name>} surface live, with default options. */
    public EventStream<Realtime.RealtimeEvent> subscribe(String surface) {
        return subscribe(surface, null);
    }

    /**
     * Follow changes of a {@code collection:<name>} surface live. Each event is {@code row_inserted},
     * {@code row_updated} or {@code row_deleted} with a {@code payload} of {@code collection} and {@code id} only (a
     * {@code Map}), so read the record by id for its values. It is the change-feed reader of
     * {@code realtime().subscribe}: it reconnects with {@code Last-Event-ID}, drops events it already delivered and
     * calls {@code onResync} when the position is lost. Changes to undeclared columns are reported too. Close the
     * stream to stop.
     */
    public EventStream<Realtime.RealtimeEvent> subscribe(String surface, SubscribeOptions options) {
        String target = checkCollectionSurface(surface, "surface");
        String where = path("subscribe");
        Map<String, String> first = null;
        if (options != null && options.fromSequence != null) {
            if (options.fromSequence < 0) throw invalid("fromSequence must be a whole number, 0 or more", ctx("fromSequence", options.fromSequence));
            first = new HashMap<>();
            first.put("fromSequence", Long.toString(options.fromSequence));
        }
        Map<String, String> q = new HashMap<>();
        q.put("surface", target);
        return Realtime.openFeed(config, where, q, first, options == null ? null : options.feed);
    }
}
