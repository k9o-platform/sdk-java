package dev.k9o;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Datasets: {@code client.analytics()}. A dataset is a typed table in the project's analytics storage that you load
 * rows into and query with SQL. Rollups and schedules keep a dataset up to date from a collection or a stream.
 *
 * <p>Wire (project plane): {@code list} {@code GET /@<slug>/analytics}; {@code create}, {@code get} and {@code delete}
 * on {@code /@<slug>/analytics/<dataset>}; {@code write}, {@code upload}, {@code materialize} and {@code read} are
 * {@code POST} on {@code .../<dataset>/write|upload|materialize|read}; {@code query} is {@code POST
 * /@<slug>/analytics/query}; {@code downloadOriginal} is {@code GET .../<dataset>/upload/original}. Rollups and
 * schedules of a dataset are under {@code .../<dataset>/rollups} and {@code .../<dataset>/schedules}.
 *
 * <p>Callers must be members of the project (an organization role alone does not grant access). Project service
 * tokens and extension tokens cannot call these routes. Where the region's analytics storage is not provisioned yet,
 * reads and writes answer 503 {@code K9O_ANALYTICS_UNAVAILABLE}: retry later.
 *
 * <p>The answers of this namespace are returned as decoded JSON ({@code Map}, {@code List}); the gateway adds fields
 * over time and the SDK does not drop them.
 */
public final class Analytics {

    private final HttpTransport.Config config;
    private final DatasetRollups rollups;
    private final DatasetSchedules schedules;

    Analytics(HttpTransport.Config config) {
        this.config = config;
        this.rollups = new DatasetRollups(config);
        this.schedules = new DatasetSchedules(config);
    }

    /** Rollups of a dataset: {@code create}, {@code list}, {@code delete}, {@code reattest}. */
    public DatasetRollups rollups() { return rollups; }

    /** Recurring materializations into a dataset: {@code create}, {@code list}, {@code delete}, {@code reattest}. */
    public DatasetSchedules schedules() { return schedules; }

    // ---- shared helpers

    static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    static String project(HttpTransport.Config config) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "dataset operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    static String segment(String value, String field) {
        if (value == null || value.isEmpty()) throw invalid(field + " is required", Map.of("field", field));
        return enc(value);
    }

    static void checkReattestDays(Integer days) {
        if (days != null && days != 7 && days != 30 && days != 90 && days != 365) {
            throw invalid("reattestIntervalDays must be 7, 30, 90 or 365", Map.of("field", "reattestIntervalDays"));
        }
    }

    /**
     * Read the list a list route answers. The gateway sends the list as {@code data} or under one key of {@code data}
     * (for example {@code data.rollups}); both are read, and anything else is an error rather than an empty list.
     */
    @SuppressWarnings("unchecked")
    static Envelope<List<Map<String, Object>>> asList(Envelope<JsonNode> env, String what) {
        JsonNode d = env.data();
        JsonNode arr = null;
        if (d != null && d.isArray()) {
            arr = d;
        } else if (d != null && d.isObject()) {
            // The list sits under one key of data (items, rows, rollups, schedules, ...): the only array-valued field.
            for (String key : List.of("items", "rows")) {
                if (d.path(key).isArray()) arr = d.get(key);
            }
            if (arr == null) {
                int arrays = 0;
                for (var it = d.fields(); it.hasNext(); ) {
                    var f = it.next();
                    if (f.getValue().isArray()) { arrays++; arr = f.getValue(); }
                }
                if (arrays != 1) arr = null;
            }
        }
        if (arr == null) {
            throw new KError("K9O_INTERNAL_ERROR", "Unexpected " + what + " answer: data is not a list");
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (JsonNode n : arr) {
            try {
                out.add((Map<String, Object>) HttpTransport.JSON.treeToValue(n, Map.class));
            } catch (JsonProcessingException | IllegalArgumentException e) {
                throw new KError("K9O_INTERNAL_ERROR", "Unexpected " + what + " answer: an item is not an object");
            }
        }
        return new Envelope<>(List.copyOf(out), env.meta());
    }

    static final TypeReference<Envelope<Map<String, Object>>> MAP = new TypeReference<>() {};
    static final TypeReference<Envelope<JsonNode>> NODE = new TypeReference<>() {};
    static final TypeReference<Envelope<Object>> ANY = new TypeReference<>() {};

    // ---- datasets

    /** A column of a dataset: {@code type} is {@code string}, {@code int32}, {@code int64}, {@code float64}, {@code bool}, {@code date}, {@code timestamp}, {@code json}, {@code bytes} or {@code decimal(p,s)}. */
    public record Column(String name, String type) {}

    /** Where a dataset's files live: {@code managed} (the platform's storage) or {@code byos} (your own, by binding id). */
    public record StorageTarget(String kind, String bindingId) {
        public static StorageTarget managed() { return new StorageTarget("managed", null); }
        public static StorageTarget byos(String bindingId) { return new StorageTarget("byos", bindingId); }
    }

    /**
     * Body of {@link #create}. {@code residencyClass} is {@code per-subject} or {@code shared}; {@code partitionBy} and
     * {@code storageTarget} may be {@code null}. A timestamp partition key needs a transform such as {@code created_at:day}.
     */
    public record CreateInput(String displayName, List<Column> columns, List<String> partitionBy, String residencyClass,
                              StorageTarget storageTarget) {
        public CreateInput(String displayName, List<Column> columns, String residencyClass) {
            this(displayName, columns, null, residencyClass, null);
        }
    }

    private static Map<String, Object> createBody(CreateInput in) {
        if (in == null) throw invalid("input is required", Map.of("field", "input"));
        if (in.displayName() == null || in.displayName().isEmpty() || in.displayName().length() > 200) {
            throw invalid("displayName must be 1 to 200 characters", Map.of("field", "displayName"));
        }
        if (in.columns() == null || in.columns().isEmpty() || in.columns().size() > 512) {
            throw invalid("columns must hold 1 to 512 columns", Map.of("field", "columns"));
        }
        if (!"per-subject".equals(in.residencyClass()) && !"shared".equals(in.residencyClass())) {
            throw invalid("residencyClass must be per-subject or shared", Map.of("field", "residencyClass"));
        }
        if (in.partitionBy() != null && in.partitionBy().size() > 16) {
            throw invalid("partitionBy holds at most 16 keys", Map.of("field", "partitionBy"));
        }
        List<Map<String, Object>> cols = new ArrayList<>();
        for (Column c : in.columns()) {
            if (c == null || c.name() == null || c.name().isEmpty() || c.type() == null || c.type().isEmpty()) {
                throw invalid("each column needs a name and a type", Map.of("field", "columns"));
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", c.name());
            m.put("type", c.type());
            cols.add(m);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("displayName", in.displayName());
        body.put("columns", cols);
        if (in.partitionBy() != null) body.put("partitionBy", in.partitionBy());
        body.put("residencyClass", in.residencyClass());
        StorageTarget t = in.storageTarget();
        if (t != null) {
            Map<String, Object> st = new LinkedHashMap<>();
            if (!"managed".equals(t.kind()) && !"byos".equals(t.kind())) {
                throw invalid("storageTarget.kind must be managed or byos", Map.of("field", "storageTarget"));
            }
            st.put("kind", t.kind());
            if ("byos".equals(t.kind())) {
                if (t.bindingId() == null || t.bindingId().isEmpty()) {
                    throw invalid("storageTarget.bindingId is required for byos", Map.of("field", "storageTarget"));
                }
                st.put("bindingId", t.bindingId());
            }
            body.put("storageTarget", st);
        }
        return body;
    }

    private String path(String tail) {
        return UrlGrammar.projectPath(project(config), "analytics" + tail);
    }

    /**
     * The project's active datasets, newest first: slug, display name, schema, partition keys ({@code region} is always
     * the first), residency class, storage target and creation time. {@code GET analytics}.
     */
    public Envelope<List<Map<String, Object>>> list() {
        return asList(HttpTransport.request(config, "GET", path(""), null, null, NODE), "datasets list");
    }

    /**
     * Create a dataset at {@code datasetSlug} ({@code POST analytics/<slug>}; answers 201). A slug is a lowercase
     * identifier, some words are reserved, and a slug is never reissued, even after the dataset is deleted. A project
     * holds at most 200 datasets (429 {@code K9O_DATASET_LIMIT_EXCEEDED}). Needs the {@code datasets:create}
     * permission (project admin or author by default).
     */
    public Envelope<Map<String, Object>> create(String datasetSlug, CreateInput input) {
        String seg = segment(datasetSlug, "datasetSlug");
        return HttpTransport.request(config, "POST", path("/" + seg), createBody(input), null, MAP);
    }

    /** One active dataset's metadata ({@code GET analytics/<slug>}). A deleted or unknown slug is 404 {@code K9O_DATASET_NOT_FOUND}. */
    public Envelope<Map<String, Object>> get(String datasetSlug) {
        return HttpTransport.request(config, "GET", path("/" + segment(datasetSlug, "datasetSlug")), null, null, MAP);
    }

    /** Retire a dataset ({@code DELETE analytics/<slug>}): it reads as absent from then on and its slug is not reissued. Answers {@code {slug, status: "retired"}}. */
    public Envelope<Map<String, Object>> delete(String datasetSlug) {
        return HttpTransport.request(config, "DELETE", path("/" + segment(datasetSlug, "datasetSlug")), null, null, MAP);
    }

    // ---- writing rows

    /** What to do with a row that does not match the schema: {@code skip} (default) or {@code reject} (the whole write fails with 400 {@code K9O_INGEST_SCHEMA_MISMATCH}). */
    public enum OnError {
        SKIP("skip"), REJECT("reject");

        private final String wire;

        OnError(String wire) { this.wire = wire; }

        public String wire() { return wire; }
    }

    /**
     * Append rows ({@code POST analytics/<slug>/write}). {@code rows} are validated against the dataset's schema. With
     * {@link OnError#SKIP} (the default, also for {@code null}) bad rows are skipped and reported. The answer holds
     * {@code ingested}, {@code skipped}, up to 100 {@code skippedDetails}, the partitions touched and the bytes written.
     */
    public Envelope<Map<String, Object>> write(String datasetSlug, List<? extends Map<String, ?>> rows, OnError onError) {
        String seg = segment(datasetSlug, "datasetSlug");
        if (rows == null || rows.isEmpty()) throw invalid("rows must hold at least one row", Map.of("field", "rows"));
        Map<String, String> q = onError == null ? null : Map.of("onError", onError.wire());
        return HttpTransport.request(config, "POST", path("/" + seg + "/write"), rows, q, MAP);
    }

    /** As {@link #write(String, List, OnError)} with the default {@code onError} (skip). */
    public Envelope<Map<String, Object>> write(String datasetSlug, List<? extends Map<String, ?>> rows) {
        return write(datasetSlug, rows, null);
    }

    /**
     * Copy rows into the dataset from exactly one source ({@code POST analytics/<slug>/materialize}): a collection name
     * ({@code fromCollection}) or a saved Studio query id that reads a single collection ({@code fromQuery}). The source is
     * read with the caller's own row-level access. Copying a collection that holds per-subject personal data into a
     * {@code shared} dataset is refused (400 {@code K9O_DATASET_RESIDENCY_SOURCE_MISMATCH}).
     */
    public record MaterializeInput(String fromCollection, String fromQuery) {
        public static MaterializeInput fromCollection(String collection) { return new MaterializeInput(collection, null); }
        public static MaterializeInput fromQuery(String queryId) { return new MaterializeInput(null, queryId); }
    }

    public Envelope<Map<String, Object>> materialize(String datasetSlug, MaterializeInput input) {
        String seg = segment(datasetSlug, "datasetSlug");
        if (input == null || (input.fromCollection() == null) == (input.fromQuery() == null)) {
            throw invalid("send exactly one of fromCollection or fromQuery", Map.of("field", "input"));
        }
        String v = input.fromCollection() != null ? input.fromCollection() : input.fromQuery();
        String field = input.fromCollection() != null ? "fromCollection" : "fromQuery";
        if (v.isEmpty() || v.length() > 128) throw invalid(field + " must be 1 to 128 characters", Map.of("field", field));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(field, v);
        return HttpTransport.request(config, "POST", path("/" + seg + "/materialize"), body, null, MAP);
    }

    /** File format of an {@link #upload}. */
    public enum Format {
        CSV("csv", "text/csv"), JSON("json", "application/json");

        private final String wire;
        private final String mime;

        Format(String wire, String mime) { this.wire = wire; this.mime = mime; }

        public String wire() { return wire; }
    }

    /**
     * Options of {@link #upload}. {@code format} is required. {@code dryRun} returns the detected columns and a 50-row
     * preview without writing. {@code delimiter}, {@code encoding} and {@code hasHeader} apply to CSV. A
     * {@code manifest} (the {@link CreateInput} fields as a map: {@code displayName}, {@code columns}, ...) on a slug that
     * does not exist yet creates the dataset and loads the file in one call; the file is then sent as multipart form
     * data (parts {@code file} and {@code manifest}); without a manifest the file is the raw request body.
     */
    public record UploadOptions(Format format, Boolean dryRun, OnError onError, String delimiter, String encoding,
                                Boolean hasHeader, Map<String, Object> manifest, String filename) {
        public static UploadOptions of(Format format) {
            return new UploadOptions(format, null, null, null, null, null, null, null);
        }
    }

    /**
     * Upload a CSV or JSON file of up to 25 MiB ({@code POST analytics/<slug>/upload}). Needs {@code datasets:write}; a
     * dry run or a manifest create needs {@code datasets:create}. The answer holds the ingest counts, and the original
     * file is kept for {@link #downloadOriginal}. The file is checked for size before it is sent.
     */
    public Envelope<Map<String, Object>> upload(String datasetSlug, byte[] file, UploadOptions options) {
        String seg = segment(datasetSlug, "datasetSlug");
        if (file == null || file.length == 0) throw invalid("file must not be empty", Map.of("field", "file"));
        if (file.length > 25L * 1024 * 1024) throw invalid("file exceeds 25 MiB", Map.of("field", "file"));
        if (options == null || options.format() == null) throw invalid("options.format is required", Map.of("field", "format"));
        UploadOptions o = options;
        Map<String, String> q = new LinkedHashMap<>();
        q.put("format", o.format().wire);
        if (o.dryRun() != null) q.put("dryRun", o.dryRun().toString());
        if (o.onError() != null) q.put("onError", o.onError().wire());
        if (o.delimiter() != null) q.put("delimiter", o.delimiter());
        if (o.encoding() != null) q.put("encoding", o.encoding());
        if (o.hasHeader() != null) q.put("hasHeader", o.hasHeader().toString());
        Object body;
        if (o.manifest() == null) {
            body = new HttpTransport.RawBody(file, o.format().mime);
        } else {
            String boundary = "----k9o" + UUID.randomUUID().toString().replace("-", "");
            ByteArrayOutputStream out = new ByteArrayOutputStream(file.length + 1024);
            String name = (o.filename() == null || o.filename().isEmpty() ? "upload." + o.format().wire : o.filename())
                .replaceAll("[\\r\\n\"\\\\]", "_");
            ascii(out, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name
                + "\"\r\nContent-Type: " + o.format().mime + "\r\n\r\n");
            out.writeBytes(file);
            ascii(out, "\r\n--" + boundary + "\r\nContent-Disposition: form-data; name=\"manifest\"\r\n"
                + "Content-Type: application/json\r\n\r\n");
            try {
                out.writeBytes(HttpTransport.JSON.writeValueAsBytes(o.manifest()));
            } catch (JsonProcessingException e) {
                throw invalid("manifest cannot be encoded as JSON", Map.of("field", "manifest"));
            }
            ascii(out, "\r\n--" + boundary + "--\r\n");
            body = new HttpTransport.RawBody(out.toByteArray(), "multipart/form-data; boundary=" + boundary);
        }
        return HttpTransport.request(config, "POST", path("/" + seg + "/upload"), body, q, MAP);
    }

    private static void ascii(ByteArrayOutputStream out, String s) {
        out.writeBytes(s.getBytes(StandardCharsets.UTF_8));
    }

    /** A downloaded file: its bytes and the {@code Content-Type} the gateway sent ({@code text/csv}, {@code application/json} or {@code application/octet-stream}). */
    public record OriginalFile(byte[] bytes, String contentType) {}

    /**
     * The original file of the dataset's stored upload ({@code GET analytics/<slug>/upload/original}); the answer is the
     * file, not a JSON envelope. 404 {@code K9O_NOT_FOUND} when none is stored.
     */
    public OriginalFile downloadOriginal(String datasetSlug) {
        HttpTransport.Download d = HttpTransport.requestBytes(config, "GET",
            path("/" + segment(datasetSlug, "datasetSlug") + "/upload/original"), null);
        return new OriginalFile(d.bytes(), d.contentType());
    }

    // ---- reading

    /**
     * Run a read-only SQL {@code SELECT} over the project's datasets ({@code POST analytics/query}). {@code sql} is at
     * most 100,000 characters; {@code datasets} (1 to 64 slugs) names the datasets it reads, each referenced as
     * {@code dataset.<slug>}. Needs a project admin, author or editor role and {@code datasets:query} on every dataset.
     * A table that is not a declared dataset is refused with 400 {@code K9O_QUERY_INVALID}.
     */
    public Envelope<Object> query(String sql, List<String> datasets) {
        project(config);
        if (sql == null || sql.isBlank() || sql.length() > 100_000) {
            throw invalid("sql must be 1 to 100,000 characters", Map.of("field", "sql"));
        }
        if (datasets == null || datasets.isEmpty() || datasets.size() > 64) {
            throw invalid("datasets must hold 1 to 64 dataset slugs", Map.of("field", "datasets"));
        }
        for (String d : datasets) {
            if (d == null || d.isEmpty()) throw invalid("a dataset slug is empty", Map.of("field", "datasets"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("sql", sql);
        body.put("datasets", datasets);
        return HttpTransport.request(config, "POST", path("/query"), body, null, ANY);
    }

    /**
     * Options of {@link #read}: {@code partitionFilter} (constraints on declared partition keys), {@code cursor} and
     * {@code limit} (clamped to a page maximum by the gateway). All fields may be {@code null}.
     */
    public record ReadOptions(Map<String, Object> partitionFilter, String cursor, Integer limit) {
        public static ReadOptions none() { return new ReadOptions(null, null, null); }
    }

    /**
     * Page through a dataset's rows ({@code POST analytics/<slug>/read}). Each row carries a stable {@code _k9o_row_id}.
     * The next page's cursor is {@code meta().cursor().next()}; pass it as {@code cursor}. Needs a project admin, author
     * or editor role and {@code datasets:query}.
     */
    public Envelope<List<Map<String, Object>>> read(String datasetSlug, ReadOptions options) {
        String seg = segment(datasetSlug, "datasetSlug");
        ReadOptions o = options == null ? ReadOptions.none() : options;
        if (o.limit() != null && o.limit() < 1) throw invalid("limit must be 1 or more", Map.of("field", "limit"));
        Map<String, Object> body = new LinkedHashMap<>();
        if (o.partitionFilter() != null) body.put("partitionFilter", o.partitionFilter());
        if (o.cursor() != null) body.put("cursor", o.cursor());
        if (o.limit() != null) body.put("limit", o.limit());
        return asList(HttpTransport.request(config, "POST", path("/" + seg + "/read"), body, null, NODE), "dataset read");
    }

    // ---- rollups of a dataset

    /**
     * Dataset rollups: {@code client.analytics().rollups()}. A rollup folds a stream into a dataset over fixed windows.
     * It keeps running only while its configuration is re-attested within its interval (7, 30, 90 or 365 days; default
     * 90): without {@link #reattest} an expired rollup is paused, not deleted. {@code reattest} is human-only.
     */
    public static final class DatasetRollups {
        private final HttpTransport.Config config;

        DatasetRollups(HttpTransport.Config config) { this.config = config; }

        private String path(String dataset, String tail) {
            return UrlGrammar.projectPath(project(config), "analytics/" + segment(dataset, "datasetSlug") + "/rollups" + tail);
        }

        /** A measure: {@code agg} is {@code count}, or {@code last}, {@code sum}, {@code avg}, {@code min}, {@code max}, {@code count_distinct}, {@code p50}, {@code p95} or {@code p99} over a {@code column}. */
        public record Measure(String name, String agg, String column) {
            public static Measure count(String name) { return new Measure(name, "count", null); }
        }

        /**
         * Body of {@link #create}. {@code slug} (1 to 63), {@code source} (the stream, 1 to 128), up to 16
         * {@code dimensions}, 1 to 64 {@code measures}, {@code windowSeconds} (60 or more) and exactly one of
         * {@code subjectField} (per-subject data, suppressed below the cohort threshold) or {@code nonPersonal} true.
         * {@code reattestIntervalDays} is 7, 30, 90 or 365 ({@code null}: the gateway default, 90).
         */
        public record CreateInput(String slug, String source, List<String> dimensions, List<Measure> measures,
                                  long windowSeconds, String subjectField, Boolean nonPersonal, Integer reattestIntervalDays) {}

        /** Create a continuous rollup ({@code POST analytics/<dataset>/rollups}). Answers the rollup with its read and subscribe endpoints. 409 {@code K9O_ALREADY_EXISTS} when the slug is taken. */
        public Envelope<Map<String, Object>> create(String datasetSlug, CreateInput in) {
            if (in == null) throw invalid("input is required", Map.of("field", "input"));
            if (in.slug() == null || in.slug().isEmpty() || in.slug().length() > 63) throw invalid("slug must be 1 to 63 characters", Map.of("field", "slug"));
            if (in.source() == null || in.source().isEmpty() || in.source().length() > 128) throw invalid("source must be 1 to 128 characters", Map.of("field", "source"));
            if (in.measures() == null || in.measures().isEmpty() || in.measures().size() > 64) throw invalid("measures must hold 1 to 64 measures", Map.of("field", "measures"));
            if (in.dimensions() != null && in.dimensions().size() > 16) throw invalid("dimensions holds at most 16 names", Map.of("field", "dimensions"));
            if (in.windowSeconds() < 60) throw invalid("windowSeconds must be 60 or more", Map.of("field", "windowSeconds"));
            boolean personal = in.subjectField() != null;
            boolean nonPersonal = Boolean.TRUE.equals(in.nonPersonal());
            if (personal == nonPersonal) {
                throw invalid("send exactly one of subjectField or nonPersonal: true", Map.of("field", "subjectField"));
            }
            checkReattestDays(in.reattestIntervalDays());
            List<Map<String, Object>> ms = new ArrayList<>();
            for (Measure m : in.measures()) {
                if (m == null || m.name() == null || m.name().isEmpty() || m.agg() == null || m.agg().isEmpty()) {
                    throw invalid("each measure needs a name and an agg", Map.of("field", "measures"));
                }
                Map<String, Object> mm = new LinkedHashMap<>();
                mm.put("name", m.name());
                mm.put("agg", m.agg());
                if (m.column() != null) mm.put("column", m.column());
                ms.add(mm);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("slug", in.slug());
            if (personal) body.put("subjectField", in.subjectField());
            else body.put("nonPersonal", true);
            body.put("source", in.source());
            if (in.dimensions() != null) body.put("dimensions", in.dimensions());
            body.put("measures", ms);
            body.put("windowSeconds", in.windowSeconds());
            if (in.reattestIntervalDays() != null) body.put("reattestIntervalDays", in.reattestIntervalDays());
            return HttpTransport.request(config, "POST", path(datasetSlug, ""), body, null, MAP);
        }

        /** The dataset's rollups with their attestation state: {@code attestedAt}, {@code expiresAt} and {@code attestationStatus} ({@code active}, {@code expiring_soon} or {@code expired}). */
        public Envelope<List<Map<String, Object>>> list(String datasetSlug) {
            return asList(HttpTransport.request(config, "GET", path(datasetSlug, ""), null, null, NODE), "rollups list");
        }

        /** Delete a rollup by id ({@code DELETE analytics/<dataset>/rollups/<id>}). Answers {@code {id, deleted: true}}. */
        public Envelope<Map<String, Object>> delete(String datasetSlug, String rollupId) {
            return HttpTransport.request(config, "DELETE", path(datasetSlug, "/" + segment(rollupId, "rollupId")), null, null, MAP);
        }

        /**
         * Confirm the rollup's configuration is still wanted: resets its attestation to now and, with
         * {@code reattestIntervalDays} (7, 30, 90 or 365; {@code null} keeps the interval), sets a new interval. An
         * expired rollup resumes. Answers the rollup with its new {@code expiresAt}.
         *
         * <p>Human only: a service, workload or extension token gets 403 {@code K9O_RBAC_DENY} with context reason
         * {@code human_only}; a personal access token acts as its person. The SDK does not special-case this: the error
         * is the gateway's.
         */
        public Envelope<Map<String, Object>> reattest(String datasetSlug, String rollupId, Integer reattestIntervalDays) {
            checkReattestDays(reattestIntervalDays);
            Map<String, Object> body = new LinkedHashMap<>();
            if (reattestIntervalDays != null) body.put("reattestIntervalDays", reattestIntervalDays);
            return HttpTransport.request(config, "POST", path(datasetSlug, "/" + segment(rollupId, "rollupId") + "/reattest"), body, null, MAP);
        }

        /** As {@link #reattest(String, String, Integer)}, keeping the current interval. */
        public Envelope<Map<String, Object>> reattest(String datasetSlug, String rollupId) {
            return reattest(datasetSlug, rollupId, null);
        }
    }

    // ---- schedules of a dataset

    /**
     * Dataset schedules: {@code client.analytics().schedules()}. A schedule copies a collection into the dataset on an
     * interval, reading with its creator's current access each run. It keeps running only while it is re-attested within
     * its interval (7, 30, 90 or 365 days); {@code reattest} is human-only.
     */
    public static final class DatasetSchedules {
        private final HttpTransport.Config config;

        DatasetSchedules(HttpTransport.Config config) { this.config = config; }

        private String path(String dataset, String tail) {
            return UrlGrammar.projectPath(project(config), "analytics/" + segment(dataset, "datasetSlug") + "/schedules" + tail);
        }

        /** Body of {@link #create}: {@code fromCollection}, {@code intervalSeconds} (60 or more) and an optional {@code reattestIntervalDays} (7, 30, 90, 365). */
        public record CreateInput(String fromCollection, long intervalSeconds, Integer reattestIntervalDays) {}

        /** Create a recurring materialization ({@code POST analytics/<dataset>/schedules}). 409 {@code K9O_PENDING_CHANGE_ALREADY_EXISTS} when one exists for this dataset and collection. */
        public Envelope<Map<String, Object>> create(String datasetSlug, CreateInput in) {
            if (in == null) throw invalid("input is required", Map.of("field", "input"));
            if (in.fromCollection() == null || in.fromCollection().isEmpty() || in.fromCollection().length() > 128) {
                throw invalid("fromCollection must be 1 to 128 characters", Map.of("field", "fromCollection"));
            }
            if (in.intervalSeconds() < 60) throw invalid("intervalSeconds must be 60 or more", Map.of("field", "intervalSeconds"));
            checkReattestDays(in.reattestIntervalDays());
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("fromCollection", in.fromCollection());
            body.put("intervalSeconds", in.intervalSeconds());
            if (in.reattestIntervalDays() != null) body.put("reattestIntervalDays", in.reattestIntervalDays());
            return HttpTransport.request(config, "POST", path(datasetSlug, ""), body, null, MAP);
        }

        /** The dataset's schedules with {@code lastRunAt}, {@code lastRunStatus}, {@code nextRunAt} and the attestation state ({@code attestedAt}, {@code expiresAt}, {@code attestationStatus}). */
        public Envelope<List<Map<String, Object>>> list(String datasetSlug) {
            return asList(HttpTransport.request(config, "GET", path(datasetSlug, ""), null, null, NODE), "schedules list");
        }

        /** Delete a schedule by id. Answers {@code {id, deleted: true}}. */
        public Envelope<Map<String, Object>> delete(String datasetSlug, String scheduleId) {
            return HttpTransport.request(config, "DELETE", path(datasetSlug, "/" + segment(scheduleId, "scheduleId")), null, null, MAP);
        }

        /**
         * Confirm the schedule is still wanted: resets its attestation to now and optionally sets a new
         * {@code reattestIntervalDays} (7, 30, 90 or 365). A paused schedule resumes. Answers the schedule with its new
         * {@code expiresAt}.
         *
         * <p>Human only: a service, workload or extension token gets 403 {@code K9O_RBAC_DENY} with context reason
         * {@code human_only}; a personal access token acts as its person. The SDK does not special-case this.
         */
        public Envelope<Map<String, Object>> reattest(String datasetSlug, String scheduleId, Integer reattestIntervalDays) {
            checkReattestDays(reattestIntervalDays);
            Map<String, Object> body = new LinkedHashMap<>();
            if (reattestIntervalDays != null) body.put("reattestIntervalDays", reattestIntervalDays);
            return HttpTransport.request(config, "POST", path(datasetSlug, "/" + segment(scheduleId, "scheduleId") + "/reattest"), body, null, MAP);
        }

        /** As {@link #reattest(String, String, Integer)}, keeping the current interval. */
        public Envelope<Map<String, Object>> reattest(String datasetSlug, String scheduleId) {
            return reattest(datasetSlug, scheduleId, null);
        }
    }
}
