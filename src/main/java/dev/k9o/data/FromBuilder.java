package dev.k9o.data;

import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.type.TypeFactory;
import com.fasterxml.jackson.databind.JsonNode;
import dev.k9o.Envelope;
import dev.k9o.GatedWrite;
import dev.k9o.HttpTransport;
import dev.k9o.KError;
import dev.k9o.UrlGrammar;

import java.util.*;
import java.util.regex.Pattern;

/**
 * Data-plane DSL.
 *
 * <pre>{@code
 * var response = k9o.from("posts").select().limit(10).execute(Post.class);
 * response.data().forEach(System.out::println);
 * }</pre>
 *
 * Chainable; terminal verbs are {@code execute(Class<T>)} for select +
 * insert/update/delete. Update/delete require {@code .eq("id", "...")}.
 */
public class FromBuilder {

    private static final Pattern COLLECTION_RE = Pattern.compile("^[a-z][a-z0-9_-]{0,62}$");

    private final HttpTransport.Config config;
    private final String collection;

    public FromBuilder(HttpTransport.Config config, String collection) {
        this.config = config;
        this.collection = collection;
    }

    private static final Pattern SEARCH_COLUMN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]{0,62}$");
    private static final Pattern LANGUAGE_RE = Pattern.compile("^[a-zA-Z_]+$");

    /** Options of {@code textSearch}; {@code null} fields use the defaults ({@code english}, {@code plain}). */
    public record TextSearchOptions(String language, String type) {}

    /** Options of {@code vectorSearch}; {@code null} fields use the defaults ({@code cosine}, gateway limit of 10). */
    public record VectorSearchOptions(String metric, Integer limit) {}

    private static KError invalidSearch(String message) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, Map.of(), null, null, null);
    }

    public SelectBuilder select() {
        return new SelectBuilder(config, collection);
    }

    public <T> Envelope<T> insert(Object record, Class<T> type) {
        String path = collectionPath(config, collection);
        return HttpTransport.request(config, "POST", path, record, null,
            envelopeOf(type));
    }

    /**
     * Insert one record. Chain {@code requireApproval()} to queue the write for approval instead of applying it:
     * {@code insert(record).requireApproval().execute(Row.class)}.
     */
    public InsertBuilder insert(Object record) {
        return new InsertBuilder(config, collection, record);
    }

    /**
     * Build {@code JavaType} for {@code Envelope<T>} from the runtime row type.
     * Generics are erased, so a {@code TypeReference<Envelope<T>>} captured here
     * would deserialize rows as {@code LinkedHashMap}; constructing the parametric
     * type from {@code rowType} makes Jackson bind the user's row class.
     */
    static JavaType envelopeOf(Class<?> rowType) {
        TypeFactory tf = HttpTransport.typeFactory();
        return tf.constructParametricType(Envelope.class, rowType);
    }

    /** Build {@code JavaType} for {@code Envelope<List<T>>} (SELECT terminal). */
    static JavaType envelopeOfList(Class<?> rowType) {
        TypeFactory tf = HttpTransport.typeFactory();
        return tf.constructParametricType(
            Envelope.class,
            tf.constructCollectionType(List.class, rowType));
    }

    public UpdateBuilder update(Object patch) {
        return new UpdateBuilder(config, collection, patch);
    }

    public DeleteBuilder delete() {
        return new DeleteBuilder(config, collection);
    }

    static String collectionPath(HttpTransport.Config config, String collection) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError(
                "K9O_MISSING_PROJECT_CONTEXT",
                "data-plane operations require projectSlug to be bound on the client",
                Map.of("collection", collection),
                "Pass .projectSlug(...) on K9o.Opts.builder() in createClient().",
                null, null
            );
        }
        if (!COLLECTION_RE.matcher(collection).matches()) {
            throw new KError(
                "K9O_INVALID_FIELD_VALUE",
                "collection name \"" + collection + "\" does not match required format",
                Map.of("collection", collection),
                null, null, null
            );
        }
        return UrlGrammar.projectPath(config.projectSlug, "data/" + collection);
    }

    /** Shared filter/page state for SELECT / UPDATE / DELETE builders. */
    abstract static class BaseBuilder<S extends BaseBuilder<S>> {
        protected final List<String[]> filters = new ArrayList<>();
        protected Integer limit;
        protected String cursor;
        protected String count;

        @SuppressWarnings("unchecked")
        protected S self() { return (S) this; }

        public S eq(String column, Object value) { filters.add(new String[]{column, "eq", String.valueOf(value)}); return self(); }
        public S neq(String column, Object value) { filters.add(new String[]{column, "neq", String.valueOf(value)}); return self(); }
        public S gt(String column, Object value) { filters.add(new String[]{column, "gt", String.valueOf(value)}); return self(); }
        public S gte(String column, Object value) { filters.add(new String[]{column, "gte", String.valueOf(value)}); return self(); }
        public S lt(String column, Object value) { filters.add(new String[]{column, "lt", String.valueOf(value)}); return self(); }
        public S lte(String column, Object value) { filters.add(new String[]{column, "lte", String.valueOf(value)}); return self(); }
        public S like(String column, String pattern) { filters.add(new String[]{column, "like", pattern}); return self(); }
        public S ilike(String column, String pattern) { filters.add(new String[]{column, "ilike", pattern}); return self(); }
        public S is(String column, String value) { filters.add(new String[]{column, "is", value}); return self(); }

        public S in(String column, List<?> values) {
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (Object v : values) sj.add(String.valueOf(v));
            filters.add(new String[]{column, "in", sj.toString()});
            return self();
        }
        public S notIn(String column, List<?> values) {
            StringJoiner sj = new StringJoiner(",", "(", ")");
            for (Object v : values) sj.add(String.valueOf(v));
            filters.add(new String[]{column, "notIn", sj.toString()});
            return self();
        }
        public S filter(String column, String op, Object value) {
            filters.add(new String[]{column, op, String.valueOf(value)});
            return self();
        }

        public S limit(int n) { this.limit = n; return self(); }
        public S cursor(String opaque) { this.cursor = opaque; return self(); }
        public S count(String mode) { this.count = mode; return self(); }

        protected Map<String, String> toQuery() {
            Map<String, String> q = new LinkedHashMap<>();
            for (String[] f : filters) {
                String val = f[2].startsWith("(") ? f[1] + "." + f[2] : f[1] + "." + f[2];
                q.put(f[0], val);
            }
            if (limit != null) q.put("limit", limit.toString());
            if (cursor != null) q.put("cursor", cursor);
            if (count != null) q.put("count", count);
            return q;
        }
    }

    /** SELECT terminal — call execute(Class<T>) to issue the request. */
    public static class SelectBuilder extends BaseBuilder<SelectBuilder> {
        private final HttpTransport.Config config;
        private final String collection;
        private final Map<String, String> searchQuery = new LinkedHashMap<>();
        private String vectorColumn;
        private List<Number> vectorEmbedding;
        private String vectorMetric;
        private Integer vectorLimit;

        SelectBuilder(HttpTransport.Config config, String collection) {
            this.config = config;
            this.collection = collection;
        }

        /** Full-text search over one text column, English and plain syntax. */
        public SelectBuilder textSearch(String column, String query) {
            return textSearch(column, query, new TextSearchOptions(null, null));
        }

        /**
         * Full-text search over one text column. Composes with filters, {@code limit} and {@code cursor}.
         * Throws {@code K9O_INVALID_FIELD_VALUE} before any request when an argument is invalid.
         */
        public SelectBuilder textSearch(String column, String query, TextSearchOptions options) {
            if (column == null || !SEARCH_COLUMN.matcher(column).matches()) {
                throw invalidSearch("textSearch column '" + column + "' is not a valid identifier");
            }
            if (query == null || query.isEmpty()) throw invalidSearch("textSearch query must be non-empty");
            String language = options == null || options.language() == null ? "english" : options.language();
            if (!LANGUAGE_RE.matcher(language).matches()) {
                throw invalidSearch("textSearch language must be a text-search configuration name");
            }
            String type = options == null || options.type() == null ? "plain" : options.type();
            if (!type.equals("plain") && !type.equals("phrase") && !type.equals("websearch")) {
                throw invalidSearch("textSearch type must be plain, phrase or websearch");
            }
            searchQuery.put("textSearch", column + ":" + query);
            if (!language.equals("english")) searchQuery.put("textSearchLanguage", language);
            else searchQuery.remove("textSearchLanguage");
            if (!type.equals("plain")) searchQuery.put("textSearchType", type);
            else searchQuery.remove("textSearchType");
            return this;
        }

        /**
         * Semantic search: the rows nearest to an embedding in one vector column, closest first (cosine). The
         * embedding travels in the request body ({@code POST data/<collection>/search}), so real embeddings work.
         * It cannot be combined with {@code textSearch}.
         */
        public SelectBuilder vectorSearch(String column, List<? extends Number> embedding) {
            return vectorSearch(column, embedding, new VectorSearchOptions(null, null));
        }

        /**
         * Semantic search with an explicit metric ({@code cosine}, {@code euclidean} or {@code dot}) and a limit of
         * 1 to 1000 rows. Throws {@code K9O_INVALID_FIELD_VALUE} before any request when an argument is invalid.
         * The result has at most the smaller of {@code limit(n)} (default 50) and the vector limit (default 10) rows;
         * set both to get more than 10.
         */
        public SelectBuilder vectorSearch(String column, List<? extends Number> embedding, VectorSearchOptions options) {
            if (column == null || !SEARCH_COLUMN.matcher(column).matches()) {
                throw invalidSearch("vectorSearch column '" + column + "' is not a valid identifier");
            }
            if (embedding == null || embedding.isEmpty()) {
                throw invalidSearch("vectorSearch embedding must be a non-empty array of numbers");
            }
            List<Number> copy = new ArrayList<>(embedding.size());
            for (Number v : embedding) {
                boolean ok = v != null && !(v instanceof Double d && !Double.isFinite(d))
                    && !(v instanceof Float f && !Float.isFinite(f));
                if (!ok) throw invalidSearch("vectorSearch embedding must be a non-empty array of numbers");
                copy.add(v);
            }
            String metric = options == null || options.metric() == null ? "cosine" : options.metric();
            if (!metric.equals("cosine") && !metric.equals("euclidean") && !metric.equals("dot")) {
                throw invalidSearch("vectorSearch metric must be cosine, euclidean or dot");
            }
            Integer lim = options == null ? null : options.limit();
            if (lim != null && (lim < 1 || lim > 1000)) {
                throw invalidSearch("vectorSearch limit must be an integer from 1 to 1000");
            }
            this.vectorColumn = column;
            this.vectorEmbedding = copy;
            this.vectorMetric = metric.equals("cosine") ? null : metric;
            this.vectorLimit = lim;
            return this;
        }

        @Override
        protected Map<String, String> toQuery() {
            Map<String, String> q = super.toQuery();
            q.putAll(searchQuery);
            return q;
        }

        private static final Set<String> TOP_LEVEL = Set.of("select", "order", "limit", "cursor", "count", "or");

        private Map<String, Object> vectorBody() {
            Map<String, Object> vs = new LinkedHashMap<>();
            vs.put("column", vectorColumn);
            vs.put("vector", vectorEmbedding);
            if (vectorMetric != null) vs.put("metric", vectorMetric);
            if (vectorLimit != null) vs.put("limit", vectorLimit);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("vectorSearch", vs);
            Map<String, String> filter = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : toQuery().entrySet()) {
                if (TOP_LEVEL.contains(e.getKey())) {
                    if (e.getKey().equals("limit")) body.put("limit", limit);
                    else body.put(e.getKey(), e.getValue());
                } else {
                    filter.put(e.getKey(), e.getValue());
                }
            }
            if (!filter.isEmpty()) body.put("filter", filter);
            return body;
        }

        public <T> Envelope<List<T>> execute(Class<T> rowType) {
            String path = collectionPath(config, collection);
            if (vectorColumn != null) {
                if (searchQuery.containsKey("textSearch")) {
                    throw invalidSearch("vectorSearch and textSearch cannot be combined on one query");
                }
                return HttpTransport.request(
                    config, "POST", path + "/search", vectorBody(), null,
                    envelopeOfList(rowType)
                );
            }
            return HttpTransport.request(
                config, "GET", path, null, toQuery(),
                envelopeOfList(rowType)
            );
        }
    }

    public static class UpdateBuilder extends BaseBuilder<UpdateBuilder> {
        private final HttpTransport.Config config;
        private final String collection;
        private final Object patch;

        UpdateBuilder(HttpTransport.Config config, String collection, Object patch) {
            this.config = config;
            this.collection = collection;
            this.patch = patch;
        }

        private String path() {
            String idVal = filters.stream()
                .filter(f -> f[0].equals("id") && f[1].equals("eq"))
                .map(f -> f[2]).findFirst().orElseThrow(() -> new KError(
                    "K9O_INVALID_FIELD_VALUE",
                    "update() requires .eq(\"id\", \"...\") filter; changing many rows in one call is not supported",
                    Map.of("collection", collection), null, null, null
                ));
            return collectionPath(config, collection) + "/" + idVal;
        }

        public <T> Envelope<T> execute(Class<T> type) {
            return HttpTransport.request(config, "PATCH", path(), patch, null,
                envelopeOf(type));
        }

        /**
         * Ask for this update to wait for approval ({@code ?requireApproval=true}). Chain {@code .eq("id", ...)}
         * first; the result is a {@link GatedWrite}: the queued change, or the updated row when an
         * {@code auto_approve} policy covers the write.
         */
        public GatedWriteBuilder requireApproval() {
            return new GatedWriteBuilder(config, "PATCH", path(), patch);
        }
    }

    public static class DeleteBuilder extends BaseBuilder<DeleteBuilder> {
        private final HttpTransport.Config config;
        private final String collection;

        DeleteBuilder(HttpTransport.Config config, String collection) {
            this.config = config;
            this.collection = collection;
        }

        private String path() {
            String idVal = filters.stream()
                .filter(f -> f[0].equals("id") && f[1].equals("eq"))
                .map(f -> f[2]).findFirst().orElseThrow(() -> new KError(
                    "K9O_INVALID_FIELD_VALUE",
                    "delete() requires .eq(\"id\", \"...\") filter; deleting many rows in one call is not supported",
                    Map.of("collection", collection), null, null, null
                ));
            return collectionPath(config, collection) + "/" + idVal;
        }

        public <T> Envelope<T> execute(Class<T> type) {
            return HttpTransport.request(config, "DELETE", path(), null, null,
                envelopeOf(type));
        }

        /**
         * Ask for this delete to wait for approval ({@code ?requireApproval=true}). Chain {@code .eq("id", ...)}
         * first; the result is a {@link GatedWrite}: the queued change, or the deleted record when an
         * {@code auto_approve} policy covers the write.
         */
        public GatedWriteBuilder requireApproval() {
            return new GatedWriteBuilder(config, "DELETE", path(), null);
        }
    }

    public static class InsertBuilder {
        private final HttpTransport.Config config;
        private final String collection;
        private final Object record;

        InsertBuilder(HttpTransport.Config config, String collection, Object record) {
            this.config = config;
            this.collection = collection;
            this.record = record;
        }

        public <T> Envelope<T> execute(Class<T> type) {
            return HttpTransport.request(config, "POST", collectionPath(config, collection), record, null,
                envelopeOf(type));
        }

        /**
         * Ask for this insert to wait for approval ({@code ?requireApproval=true}). The result is a
         * {@link GatedWrite}: the queued change (HTTP 202), or the inserted row when an {@code auto_approve}
         * policy covers the write.
         */
        public GatedWriteBuilder requireApproval() {
            return new GatedWriteBuilder(config, "POST", collectionPath(config, collection), record);
        }
    }

    /** A single-record write that asks to be queued; {@link #execute} sends it. */
    public static class GatedWriteBuilder {
        private final HttpTransport.Config config;
        private final String method;
        private final String path;
        private final Object body;

        GatedWriteBuilder(HttpTransport.Config config, String method, String path, Object body) {
            this.config = config;
            this.method = method;
            this.path = path;
            this.body = body;
        }

        /** {@code rowType} is the row class of the applied case. Test the answer with {@link GatedWrite#isQueued()}. */
        public <T> GatedWrite<T> execute(Class<T> rowType) {
            Envelope<JsonNode> raw = HttpTransport.request(config, method, path, body,
                Map.of("requireApproval", "true"), envelopeOf(JsonNode.class));
            return GatedWrite.of(raw, rowType);
        }
    }
}
