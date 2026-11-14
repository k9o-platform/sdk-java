package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Vector collections: {@code client.vectors()}. A vector collection stores embeddings you computed yourself, next to
 * the text and metadata they came from, and answers nearest-neighbour searches over them.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code nearest(collection, queryVector, options)}: {@code POST /@<slug>/vectors/<collection>/search}</li>
 * <li>{@code upsert(collection, rows)}: {@code POST /@<slug>/vectors/<collection>/upsert}</li>
 * </ul>
 *
 * <p>Both send the vector in the request body, so they take real embeddings of any common size (up to 16000 numbers).
 * The collection name must start with {@code _}. The first upsert creates the collection and fixes its dimension;
 * every later vector must have the same length. Searching a collection that was never upserted answers 404
 * {@code K9O_COLLECTION_NOT_FOUND}; a query of another length answers 400 {@code K9O_INVALID_VECTOR_DIMENSION}.
 */
public final class Vectors {

    private static final int MAX_VECTOR = 16000;
    private static final int MAX_ROWS = 1000;
    private static final Set<String> METRICS = Set.of("cosine", "euclidean", "dot");

    private final HttpTransport.Config config;

    Vectors(HttpTransport.Config config) {
        this.config = config;
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private String path(String collection, String tail) {
        if (collection == null || !collection.startsWith("_")) {
            throw invalid("a vector collection name must start with _", Map.of("collection", String.valueOf(collection)));
        }
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "vectors operations require projectSlug bound on the client");
        }
        String enc = URLEncoder.encode(collection, StandardCharsets.UTF_8).replace("+", "%20");
        return UrlGrammar.projectPath(config.projectSlug, "vectors/" + enc + "/" + tail);
    }

    private static void checkVector(List<? extends Number> vector, String name) {
        if (vector == null || vector.isEmpty() || vector.size() > MAX_VECTOR) {
            throw invalid(name + " must be a list of 1 to " + MAX_VECTOR + " numbers", Map.of());
        }
        for (Number n : vector) {
            if (n == null || Double.isNaN(n.doubleValue()) || Double.isInfinite(n.doubleValue())) {
                throw invalid(name + " must contain only finite numbers", Map.of());
            }
        }
    }

    /** Where a vector came from: a source handle and the index of the chunk within it. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Source(@JsonProperty("handle") String handle, @JsonProperty("chunkIndex") int chunkIndex) {}

    /**
     * Options of {@link #nearest}; fields left {@code null} are not sent. {@code topK}: 1 to 1000 (the gateway
     * defaults to 10). {@code minScore}: keep only results whose score is at least this. {@code metric}:
     * {@code cosine} (default), {@code euclidean} or {@code dot}. {@code filters}: equality checks on {@code id},
     * {@code contentHash}, {@code source.<key>} and {@code metadata.<key>}; values are strings, numbers or booleans,
     * and every filter must match.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record NearestOptions(
        @JsonProperty("topK") Integer topK,
        @JsonProperty("minScore") Double minScore,
        @JsonProperty("metric") String metric,
        @JsonProperty("filters") Map<String, Object> filters
    ) {
        public static NearestOptions topK(int topK) {
            return new NearestOptions(topK, null, null, null);
        }
    }

    /** One search result. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hit(
        @JsonProperty("id") String id,
        /** Higher is more similar. {@code cosine}: 1 minus the distance; {@code euclidean}: 1 / (1 + distance); {@code dot}: the inner product. */
        @JsonProperty("score") double score,
        @JsonProperty("_source") Source source,
        /** {@code null} when none was stored. */
        @JsonProperty("contentHash") String contentHash,
        /** {@code null} when none was stored. */
        @JsonProperty("text") String text,
        @JsonProperty("metadata") Map<String, Object> metadata,
        /** From {@code metadata.model}; {@code null} when not set. */
        @JsonProperty("model") String model,
        /** From {@code _source.chunkIndex}; {@code null} when not set. */
        @JsonProperty("chunk") Integer chunk
    ) {}

    /** The result of {@link #nearest}: {@code results} closest first. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record NearestResult(
        @JsonProperty("results") List<Hit> results,
        @JsonProperty("collection") String collection
    ) {}

    /**
     * One row of {@link #upsert}. {@code vector}: 1 to 16000 numbers, the same length in every row of a request.
     * {@code source} is sent as {@code _source}. Optional, {@code null} is not sent: {@code id} (1 to 256
     * characters, generated if absent; an existing id is overwritten), {@code contentHash} (at most 128
     * characters), {@code text} (at most 100000; stored sealed when the row has a subject), {@code metadata}
     * (default {@code {}}; stored in plain text, so keep only values you filter on) and {@code subjectIds} (the one
     * end user the row belongs to, {@code ["eu_..."]}; required on a per-subject collection, which is the default
     * for a new one, and refused on a collection declared shared).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record Row(
        @JsonProperty("id") String id,
        @JsonProperty("vector") List<Double> vector,
        @JsonProperty("_source") Source source,
        @JsonProperty("contentHash") String contentHash,
        @JsonProperty("text") String text,
        @JsonProperty("metadata") Map<String, Object> metadata,
        @JsonProperty("subjectIds") List<String> subjectIds
    ) {
        public Row(String id, List<Double> vector, Source source) {
            this(id, vector, source, null, null, null, null);
        }
    }

    /** The result of {@link #upsert}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record UpsertResult(
        @JsonProperty("upserted") int upserted,
        @JsonProperty("collection") String collection
    ) {}

    /** The rows nearest to {@code queryVector}, closest first, with the gateway's default options. */
    public Envelope<NearestResult> nearest(String collection, List<? extends Number> queryVector) {
        return nearest(collection, queryVector, null);
    }

    /**
     * The rows nearest to {@code queryVector} (1 to 16000 finite numbers), closest first. {@code options} may be
     * {@code null}. Answers 404 when the collection has never been upserted.
     */
    public Envelope<NearestResult> nearest(String collection, List<? extends Number> queryVector, NearestOptions options) {
        String where = path(collection, "search");
        checkVector(queryVector, "queryVector");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("queryVector", queryVector);
        if (options != null) {
            Integer topK = options.topK();
            if (topK != null && (topK < 1 || topK > 1000)) {
                throw invalid("topK must be a whole number from 1 to 1000", Map.of("topK", topK));
            }
            Double minScore = options.minScore();
            if (minScore != null && (minScore.isNaN() || minScore.isInfinite())) {
                throw invalid("minScore must be a number", Map.of());
            }
            if (options.metric() != null && !METRICS.contains(options.metric())) {
                throw invalid("metric must be one of: cosine, euclidean, dot", Map.of("metric", options.metric()));
            }
            if (topK != null) body.put("topK", topK);
            if (minScore != null) body.put("minScore", minScore);
            if (options.metric() != null) body.put("metric", options.metric());
            if (options.filters() != null) body.put("filters", options.filters());
        }
        return HttpTransport.request(config, "POST", where, body, null,
            new TypeReference<Envelope<NearestResult>>() {});
    }

    /** Insert or overwrite 1 to 1000 rows in one request. The first upsert creates the collection. */
    public Envelope<UpsertResult> upsert(String collection, List<Row> rows) {
        String where = path(collection, "upsert");
        if (rows == null || rows.isEmpty() || rows.size() > MAX_ROWS) {
            throw invalid("rows must be a list of 1 to " + MAX_ROWS + " rows", Map.of());
        }
        for (int i = 0; i < rows.size(); i++) {
            Row row = rows.get(i);
            if (row == null) throw invalid("rows[" + i + "] must be an object", Map.of());
            checkVector(row.vector(), "rows[" + i + "].vector");
        }
        return HttpTransport.request(config, "POST", where, Map.of("rows", rows), null,
            new TypeReference<Envelope<UpsertResult>>() {});
    }
}
