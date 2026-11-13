package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Function lifecycle namespace — {@code client.functions()}.
 */
public final class Functions {

    private final HttpTransport.Config config;

    Functions(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    private String requireProjectSlug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "functions operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    /** Function record returned by gateway endpoints. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FunctionRecord(
        @JsonProperty("id") String id,
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("status") String status,
        @JsonProperty("tier") String tier,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("updatedAt") String updatedAt
    ) {}

    /**
     * Deploy input; property names are the gateway's. The function's bytes are uploaded first
     * ({@link #uploadArtifact} returns the {@code artifactKey}); deploy references that artifact.
     * Functions run in the sandbox: {@code tier} is {@code sandbox} (an OCI image archive) and {@code runtime} is one
     * of bun, python, go, java, kotlin, dotnet, swift, php, ruby, rust, zig. {@code gateway} (WebAssembly in the
     * gateway) is for the platform operator and is refused for a customer deploy; tinygo and assemblyscript run only
     * there, so they are accepted here for operators but cannot be deployed to a project. {@code version} is a label for this deploy (1 to 64 characters).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record DeployFunctionInput(
        @JsonProperty("name") String name,
        @JsonProperty("runtime") String runtime,
        @JsonProperty("tier") String tier,
        @JsonProperty("artifactKey") String artifactKey,
        @JsonProperty("version") String version,
        @JsonProperty("description") String description
    ) {
        public DeployFunctionInput(String name, String runtime, String tier, String artifactKey, String version) {
            this(name, runtime, tier, artifactKey, version, null);
        }
    }

    /** Optional invoke controls; property names are the gateway's. Null fields are not sent. */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record InvokeOptions(
        @JsonProperty("background") Boolean background,
        @JsonProperty("onBehalfOf") String onBehalfOf,
        @JsonProperty("confinedTo") String confinedTo
    ) {}

    /** Result of {@link #uploadArtifact}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Artifact(@JsonProperty("artifactKey") String artifactKey) {}

    /**
     * Upload a function artifact and get back its content-addressed {@code artifactKey}, to pass to {@link #deploy}.
     * {@code POST /@<projectSlug>/functions/artifacts} with the raw bytes: a WebAssembly module for the gateway tier (for the platform operator; a project's deploy to it is refused)
     * ({@code kind} "wasm", up to 16 MiB) or an OCI image archive for the sandbox tier ({@code kind} "tar", up to
     * 256 MiB). Uploading the same bytes again returns the same key.
     */
    public Envelope<Artifact> uploadArtifact(byte[] bytes, String kind) {
        String slug = requireProjectSlug();
        String contentType;
        if ("wasm".equals(kind)) contentType = "application/wasm";
        else if ("tar".equals(kind)) contentType = "application/x-tar";
        else throw new KError("K9O_INVALID_FIELD_VALUE", "kind must be \"wasm\" or \"tar\"",
            Map.of("field", "kind", "reason", "unsupported"), null, null, null);
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions/artifacts"),
            new HttpTransport.RawBody(bytes, contentType), null, new TypeReference<Envelope<Artifact>>() {});
    }

    /** Deploy or redeploy a function from an uploaded artifact. Requires PROJECT_ADMIN. */
    public Envelope<FunctionRecord> deploy(DeployFunctionInput input) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions"),
            input, null, new TypeReference<Envelope<FunctionRecord>>() {});
    }

    /** List functions, optionally filtered by status. */
    public Envelope<java.util.List<FunctionRecord>> list(String status) {
        return list(status, null, null);
    }

    /** List functions (cursor pagination); null arguments are not sent. */
    public Envelope<java.util.List<FunctionRecord>> list(String status, Integer limit, String cursor) {
        String slug = requireProjectSlug();
        Map<String, String> q = new java.util.LinkedHashMap<>();
        if (status != null) q.put("status", status);
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (cursor != null) q.put("cursor", cursor);
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "functions"),
            null, q, new TypeReference<Envelope<java.util.List<FunctionRecord>>>() {});
    }

    public Envelope<FunctionRecord> get(String name) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug, "functions/" + enc(name)),
            null, null, new TypeReference<Envelope<FunctionRecord>>() {});
    }

    public Envelope<Map<String, Object>> delete(String name) {
        String slug = requireProjectSlug();
        return HttpTransport.request(config, "DELETE", UrlGrammar.projectPath(slug, "functions/" + enc(name)),
            null, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    /** Invoke a function; {@code payload} is sent as the {@code payload} field of the body and reaches the runtime verbatim. */
    public Envelope<Map<String, Object>> invoke(String name, Object payload) {
        return invoke(name, payload, null);
    }

    /** As {@link #invoke(String, Object)} with the gateway's invoke controls. */
    public Envelope<Map<String, Object>> invoke(String name, Object payload, InvokeOptions options) {
        String slug = requireProjectSlug();
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        if (payload != null) body.put("payload", payload);
        if (options != null) {
            if (options.background() != null) body.put("background", options.background());
            if (options.onBehalfOf() != null) body.put("onBehalfOf", options.onBehalfOf());
            if (options.confinedTo() != null) body.put("confinedTo", options.confinedTo());
        }
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "functions/" + enc(name) + "/invoke"),
            body, null, new TypeReference<Envelope<Map<String, Object>>>() {});
    }

    // ---- Event triggers ----

    private static final Set<String> TRIGGER_EVENTS = Set.of("insert", "update", "delete", "upsert");
    private static final Pattern TRIGGER_COLLECTION_RE = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private static String requireText(String value, String what) {
        if (value == null || value.isEmpty()) throw invalid(what + " must be a non-empty string", Map.of());
        return value;
    }

    private static void checkEvents(List<String> events) {
        if (events == null || events.isEmpty() || events.size() > 4 || !events.stream().allMatch(e -> e != null && TRIGGER_EVENTS.contains(e))) {
            throw invalid("events must list 1 to 4 of insert, update, delete, upsert",
                events == null ? Map.of() : Map.of("events", events));
        }
    }

    /** An event trigger: runs the function when a record event happens on a collection. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Trigger(
        /** UUID. */
        @JsonProperty("id") String id,
        @JsonProperty("functionName") String functionName,
        @JsonProperty("collection") String collection,
        /** Any of {@code insert}, {@code update}, {@code delete}, {@code upsert}. */
        @JsonProperty("events") List<String> events,
        /** A policy-grammar predicate over the record, or {@code null} to fire on every matching event. */
        @JsonProperty("filter") Map<String, Object> filter,
        @JsonProperty("enabled") boolean enabled,
        @JsonProperty("logOkInvocations") boolean logOkInvocations,
        @JsonProperty("createdAt") String createdAt
    ) {}

    /**
     * Input of {@link Triggers#create}. {@code collection}: an existing collection,
     * {@code ^[a-zA-Z_][a-zA-Z0-9_]*$}, 1 to 128 characters. {@code events}: 1 to 4 of {@code insert},
     * {@code update}, {@code delete}, {@code upsert}. Optional fields left {@code null} are not sent: {@code filter}
     * (omit to fire on every event), {@code enabled} (gateway default true), {@code logOkInvocations} (default false).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateTriggerInput(
        @JsonProperty("collection") String collection,
        @JsonProperty("events") List<String> events,
        @JsonProperty("filter") Map<String, Object> filter,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("logOkInvocations") Boolean logOkInvocations
    ) {
        public CreateTriggerInput(String collection, List<String> events) {
            this(collection, events, null, null, null);
        }
    }

    /** Fields to change in {@link Triggers#update}; set at least one. The collection cannot be changed. */
    public static final class UpdateTriggerInput {
        private final Map<String, Object> fields = new LinkedHashMap<>();

        /** Replaces the whole list. */
        public UpdateTriggerInput events(List<String> events) { fields.put("events", events); return this; }
        /** Replaces the filter. */
        public UpdateTriggerInput filter(Map<String, Object> filter) { fields.put("filter", filter); return this; }
        /** Clears the filter, so the trigger fires on every matching event. */
        public UpdateTriggerInput clearFilter() { fields.put("filter", null); return this; }
        public UpdateTriggerInput enabled(boolean enabled) { fields.put("enabled", enabled); return this; }
        public UpdateTriggerInput logOkInvocations(boolean on) { fields.put("logOkInvocations", on); return this; }
    }

    /**
     * Event triggers, {@code client.functions().triggers()}: run a function when a record is inserted, updated,
     * deleted or upserted in a collection. Needs the project admin role. For triggers on a timetable see
     * {@link Schedules}.
     */
    public final class Triggers {
        private String path(String name, String tail) {
            return UrlGrammar.projectPath(requireProjectSlug(), "functions/" + enc(requireText(name, "function name")) + "/triggers" + tail);
        }

        /** Every trigger of the function, newest first. Not paginated. */
        public Envelope<List<Trigger>> list(String name) {
            return HttpTransport.request(config, "GET", path(name, ""), null, null,
                new TypeReference<Envelope<List<Trigger>>>() {});
        }

        /** Create an event trigger. Answers 201 with the trigger. */
        public Envelope<Trigger> create(String name, CreateTriggerInput input) {
            if (input == null) throw invalid("input must be an object", Map.of());
            if (input.collection() == null || input.collection().length() > 128
                || !TRIGGER_COLLECTION_RE.matcher(input.collection()).matches()) {
                throw invalid("collection must match ^[a-zA-Z_][a-zA-Z0-9_]*$ (1 to 128 characters)",
                    input.collection() == null ? Map.of() : Map.of("collection", input.collection()));
            }
            checkEvents(input.events());
            return HttpTransport.request(config, "POST", path(name, ""), input, null,
                new TypeReference<Envelope<Trigger>>() {});
        }

        /** Change a trigger. Set at least one of {@code events}, {@code filter}, {@code enabled}, {@code logOkInvocations}. */
        public Envelope<Trigger> update(String name, String triggerId, UpdateTriggerInput patch) {
            if (patch == null || patch.fields.isEmpty()) {
                throw invalid("patch needs at least one of: events, filter, enabled, logOkInvocations", Map.of());
            }
            @SuppressWarnings("unchecked")
            List<String> events = (List<String>) patch.fields.get("events");
            if (patch.fields.containsKey("events")) checkEvents(events);
            return HttpTransport.request(config, "PATCH", path(name, "/" + enc(requireText(triggerId, "trigger id"))), patch.fields, null,
                new TypeReference<Envelope<Trigger>>() {});
        }

        /** Delete a trigger. Answers 204 with no body, so {@code data()} is {@code null}; deleting again is a 404. */
        public Envelope<Void> delete(String name, String triggerId) {
            return HttpTransport.request(config, "DELETE", path(name, "/" + enc(requireText(triggerId, "trigger id"))), null, null,
                new TypeReference<Envelope<Void>>() {});
        }
    }

    private final Triggers triggers = new Triggers();

    /** Event triggers of a function. */
    public Triggers triggers() { return triggers; }
}
