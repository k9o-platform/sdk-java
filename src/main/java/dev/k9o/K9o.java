package dev.k9o;

import dev.k9o.data.FromBuilder;

import java.net.http.HttpClient;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * k9o — Kilimanjaro platform SDK for Java.
 *
 * Idiomatic Java: camelCase methods + throw
 * {@link KError} extends RuntimeException + builder-pattern client opts.
 *
 * <pre>{@code
 * K9oClient k9o = K9o.createClient(K9o.Opts.builder()
 *     .baseUrl("https://acme.api.k9o.io")
 *     .apiKey(System.getenv("K9O_API_KEY"))
 *     .orgSlug("acme")
 *     .projectSlug("member-portal")
 *     .build());
 *
 * var resp = k9o.from("posts").select().limit(10).execute(Post.class);
 * resp.data().forEach(System.out::println);
 *
 * try {
 *     var session = k9o.gate().signIn(new Gate.SignInInput("u@x.com", "..."));
 * } catch (KError e) {
 *     System.err.printf("[%s] %s%n", e.getCode(), e.getMessage());
 * }
 * }</pre>
 */
public final class K9o {

    private K9o() {}

    /** Factory — constructs a {@link K9oClient} bound to the supplied opts. */
    public static K9oClient createClient(Opts opts) {
        String normalised = UrlGrammar.resolveBaseUrl(opts.baseUrl, opts.orgSlug);
        HttpTransport.Config config = new HttpTransport.Config(
            normalised,
            opts.apiKey,
            opts.orgSlug,
            opts.projectSlug,
            opts.defaultHeaders,
            opts.httpClient,
            opts.retryConfig,
            opts.expectedKeyRefs
        );
        return new K9oClient(config);
    }

    /** Builder-pattern client options. */
    public static final class Opts {
        final String baseUrl;
        final String apiKey;
        final String orgSlug;
        final String projectSlug;
        final Map<String, String> defaultHeaders;
        final HttpClient httpClient;
        final RetryConfig retryConfig;
        final List<String> expectedKeyRefs;

        private Opts(Builder b) {
            this.baseUrl = b.baseUrl;
            this.apiKey = b.apiKey;
            this.orgSlug = b.orgSlug;
            this.projectSlug = b.projectSlug;
            this.defaultHeaders = Map.copyOf(b.defaultHeaders);
            this.httpClient = b.httpClient;
            this.retryConfig = b.retryConfig;
            this.expectedKeyRefs = Crypto.checkExpectedKeyRefs(b.expectedKeyRefs);
        }

        public static Builder builder() {
            return new Builder();
        }

        public static final class Builder {
            private String baseUrl;
            private String apiKey;
            private String orgSlug;
            private String projectSlug;
            private final Map<String, String> defaultHeaders = new HashMap<>();
            private HttpClient httpClient;
            private RetryConfig retryConfig;
            private List<String> expectedKeyRefs;

            /** Optional gateway host root (scheme + host; no path). Defaults to the public cloud; with {@code orgSlug} on a platform host ({@code api.<apex>}) the org host is composed. */
            public Builder baseUrl(String v) { this.baseUrl = v; return this; }
            public Builder apiKey(String v) { this.apiKey = v; return this; }
            public Builder orgSlug(String v) { this.orgSlug = v; return this; }
            public Builder projectSlug(String v) { this.projectSlug = v; return this; }
            public Builder defaultHeader(String k, String v) { this.defaultHeaders.put(k, v); return this; }
            public Builder httpClient(HttpClient c) { this.httpClient = c; return this; }
            public Builder retryConfig(RetryConfig c) { this.retryConfig = c; return this; }
            /**
             * The key identifiers (ARNs, resource names, key URLs) your own KMS or HSM keys have. Set it when the project uses
             * BYOK or HYOK: the registered {@link Crypto.ClientCore} receives it on every call and must refuse, before it uses
             * any token or makes any network call, a key that is not in the list. Version is ignored in the comparison. Take
             * the values from your own cloud configuration, never from the platform, and scope the cloud role to those keys,
             * never {@code *}. Optional; when given it must not be empty, and no entry may be blank, hold whitespace or
             * contain {@code *} ({@code K9O_INVALID_FIELD_VALUE}, checked at {@code build()}).
             */
            public Builder expectedKeyRefs(List<String> refs) { this.expectedKeyRefs = refs; return this; }

            public Opts build() {
                // baseUrl is OPTIONAL — when omitted it
                // defaults to the public-cloud platform host in resolveBaseUrl.
                return new Opts(this);
            }
        }
    }

    /**
     * The customer-facing client returned by {@link K9o#createClient}.
     */
    public static final class K9oClient {
        final HttpTransport.Config config;
        private final Projects projects;
        private final Gate gate;
        private final Studio studio;
        private final Compute compute;
        private final Ai ai;
        private final Realtime realtime;
        private final Streams streams;
        private final Org org;
        private final Orgs orgs;
        private final Migrations migrations;
        private final Functions functions;
        private final Policies policies;
        private final Approvals approvals;
        private final Schedules schedules;
        private final Vectors vectors;
        private final Custody custody;
        private final Metadata metadata;
        private final Rampart rampart;
        private final EndUsers endUsers;
        private final ApprovalPolicies approvalPolicies;
        private final Schema schema;
        private final Audit audit;
        private final Analytics analytics;
        private final Rollups rollups;
        private final Keys keys;
        private final Crypto crypto;
        private final Me me;
        private final Kibo kibo;
        private final Storage storage;

        K9oClient(HttpTransport.Config config) {
            this.config = config;
            this.projects = new Projects(config);
            this.gate = new Gate(config);
            this.studio = new Studio(config);
            this.compute = new Compute(config);
            this.ai = new Ai(config);
            this.realtime = new Realtime(config);
            this.streams = new Streams(config);
            this.org = new Org(config);
            this.orgs = new Orgs(config);
            this.migrations = new Migrations(config);
            this.functions = new Functions(config);
            this.policies = new Policies(config);
            this.approvals = new Approvals(config);
            this.schedules = new Schedules(config);
            this.vectors = new Vectors(config);
            this.custody = new Custody(config);
            this.metadata = new Metadata(config);
            this.rampart = new Rampart(config);
            this.endUsers = new EndUsers(config);
            this.approvalPolicies = new ApprovalPolicies(config);
            this.schema = new Schema(config);
            this.audit = new Audit(config);
            this.analytics = new Analytics(config);
            this.rollups = new Rollups(config);
            this.keys = new Keys(config);
            this.crypto = new Crypto(config);
            this.me = new Me(config);
            this.kibo = new Kibo(config);
            this.storage = new Storage(config);
        }

        /** Data-plane DSL entry point. */
        public FromBuilder from(String collection) {
            return new FromBuilder(config, collection);
        }

        /**
         * Run one read-only SQL statement with positional parameters ({@code $1, $2, ...}). The SQL is sent as written.
         * Values are strings, numbers, booleans or {@code null}.
         */
        public Envelope<java.util.List<Map<String, Object>>> query(String sql, java.util.List<?> params) {
            return Query.run(config, sql, params == null ? java.util.List.of() : params, null);
        }

        /** Run one read-only SQL statement with named parameters: {@code :name} in the SQL is bound to the map's value. */
        public Envelope<java.util.List<Map<String, Object>>> query(String sql, Map<String, ?> params) {
            return Query.run(config, sql, null, params == null ? Map.of() : params);
        }

        /** Run one read-only SQL statement with no parameters. */
        public Envelope<java.util.List<Map<String, Object>>> query(String sql) {
            return Query.run(config, sql, java.util.List.of(), null);
        }

        /** Full-text search over one text column; rows come back as maps, best matches first. */
        public Envelope<java.util.List<Map<String, Object>>> search(String collection, Search.Input input) {
            return Search.runMaps(config, collection, input);
        }

        /** Full-text search over one text column; rows are bound to {@code rowType}. */
        public <T> Envelope<java.util.List<T>> search(String collection, Search.Input input, Class<T> rowType) {
            return Search.run(config, collection, input, rowType);
        }

        /** Project lifecycle namespace. */
        public Projects projects() {
            return projects;
        }

        /** Gate (IAK) auth namespace; project-scoped. */
        public Gate gate() {
            return gate;
        }

        /** Studio namespace — workflows + dashboards. */
        public Studio studio() { return studio; }

        /** Compute namespace — LLMs registry. */
        public Compute compute() { return compute; }

        /** AI namespace — platform AI primitives ({@code ai().embed(...)}). */
        public Ai ai() { return ai; }

        /** Realtime subscriptions namespace. */
        public Realtime realtime() { return realtime; }

        /** User data streams namespace — create, publish, replay, subscribe. */
        public Streams streams() { return streams; }

        /** Org-plane bound namespace — members/groups/smtp/auditLog/gate/defaultLLM. */
        public Org org() { return org; }

        /** Org lifecycle namespace (collection-style). */
        public Orgs orgs() { return orgs; }

        /** Migration lifecycle namespace. */
        public Migrations migrations() { return migrations; }

        /** Function lifecycle namespace. */
        public Functions functions() { return functions; }

        /** Policy bundle lifecycle namespace. */
        public Policies policies() { return policies; }

        /** Pending-change queue: list, get, approve, reject and bulk decisions. */
        public Approvals approvals() { return approvals; }

        /** Scheduled triggers: list, get, create, update, delete, runs, runNow. */
        public Schedules schedules() { return schedules; }

        /** Vector collections: nearest and upsert over embeddings you computed yourself. */
        public Vectors vectors() { return vectors; }

        /** Key custody of the project: read-only status. */
        public Custody custody() { return custody; }

        /** Metadata: filter, set, search, nearest, subscribe and the column declaration of a collection. */
        public Metadata metadata() { return metadata; }

        /** Rampart: classify content (advisory) and verify the signed receipt. */
        public Rampart rampart() { return rampart; }

        /** The project's end users and the roles they hold. */
        public EndUsers endUsers() { return endUsers; }

        /** Approval policies: the rules that decide which writes are queued. */
        public ApprovalPolicies approvalPolicies() { return approvalPolicies; }

        /** Schema namespace: the project's collections. */
        public Schema schema() { return schema; }

        /** Project-plane audit log namespace. */
        public Audit audit() { return audit; }

        /** Datasets: tables of rows you load and query with SQL; their rollups and schedules. */
        public Analytics analytics() { return analytics; }

        /** Rollups across the project: list, read cells, follow closed cells live. */
        public Rollups rollups() { return rollups; }

        /** Key inventory of the project: list, get and rotate (metadata only, never key material). */
        public Keys keys() { return keys; }

        /** Server-side application encryption: encrypt, decrypt and rewrap batches under the project's key. */
        public Crypto crypto() { return crypto; }

        /** Operator-self namespace per console/18. */
        public Me me() { return me; }

        /** Kibo Zero-Knowledge vault namespace — {@code kibo().vault().secrets()}. */
        public Kibo kibo() { return kibo; }

        /** Storage namespace: buckets and objects ({@code storage().from(bucket)} for objects). */
        public Storage storage() { return storage; }

        /** Bound baseUrl (read-only for advanced use). */
        public String baseUrl() {
            return config.baseUrl;
        }
    }
}
