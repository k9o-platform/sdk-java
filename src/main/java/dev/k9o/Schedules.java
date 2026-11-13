package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Scheduled triggers: {@code client.schedules()}. A schedule runs a function on a cron timetable.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code list(status, functionName, limit, cursor)}: {@code GET /@<slug>/forge/schedules} ({@code functionName} is sent as {@code function_name})</li>
 * <li>{@code get(id)}: {@code GET /@<slug>/forge/schedules/<id>}</li>
 * <li>{@code create(input)}: {@code POST /@<slug>/forge/schedules}</li>
 * <li>{@code update(id, patch)}: {@code PATCH /@<slug>/forge/schedules/<id>}</li>
 * <li>{@code delete(id)}: {@code DELETE /@<slug>/forge/schedules/<id>} (204, no body)</li>
 * <li>{@code runs(id, limit, cursor)}: {@code GET /@<slug>/forge/schedules/<id>/runs}</li>
 * <li>{@code runNow(id)}: {@code POST /@<slug>/forge/schedules/<id>/run-now} (202)</li>
 * </ul>
 *
 * <p>Lists are cursor-paginated, newest first: pass {@code meta().cursor().next()} of one page as {@code cursor}
 * of the next until it is {@code null}. An interval under 5 minutes, or {@code catchupPolicy} {@code run_all},
 * can be refused with 403 {@code K9O_TIER_FEATURE_GATED}.
 */
public final class Schedules {

    private final HttpTransport.Config config;

    Schedules(HttpTransport.Config config) {
        this.config = config;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "schedules operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "forge/schedules" + tail);
    }

    private String one(String id, String tail) {
        if (id == null || id.isEmpty()) throw invalid("schedule id must be a non-empty string", Map.of());
        return path("/" + enc(id) + tail);
    }

    private static void checkLimit(Integer limit) {
        if (limit != null && (limit < 1 || limit > 200)) {
            throw invalid("limit must be a whole number from 1 to 200", Map.of("limit", limit));
        }
    }

    /** A schedule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Schedule(
        /** UUID. */
        @JsonProperty("id") String id,
        @JsonProperty("functionName") String functionName,
        /** Five-field cron. An alias such as {@code @daily} is stored and returned expanded. */
        @JsonProperty("scheduleExpression") String scheduleExpression,
        @JsonProperty("scheduleTimezone") String scheduleTimezone,
        /** {@code skip}, {@code run_once} or {@code run_all}. */
        @JsonProperty("catchupPolicy") String catchupPolicy,
        @JsonProperty("collection") String collection,
        @JsonProperty("enabled") boolean enabled,
        /** {@code active} when {@code enabled}, otherwise {@code paused}. */
        @JsonProperty("status") String status,
        @JsonProperty("logOkInvocations") boolean logOkInvocations,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("lastInvocationAt") String lastInvocationAt,
        @JsonProperty("nextInvocationAt") String nextInvocationAt
    ) {}

    /** One run of a schedule. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Run(
        @JsonProperty("id") String id,
        @JsonProperty("triggerId") String triggerId,
        @JsonProperty("functionName") String functionName,
        @JsonProperty("collection") String collection,
        @JsonProperty("event") String event,
        @JsonProperty("status") String status,
        @JsonProperty("attemptCount") int attemptCount,
        @JsonProperty("errorDetail") String errorDetail,
        @JsonProperty("errorOutput") String errorOutput,
        @JsonProperty("recordId") String recordId,
        @JsonProperty("triggeredAt") String triggeredAt,
        @JsonProperty("completedAt") String completedAt
    ) {}

    /**
     * Input of {@link #create}. {@code functionName}: an existing function that is not retired.
     * {@code scheduleExpression}: five-field cron (minute 0-59, hour 0-23, day 1-31, month 1-12, weekday 0-6) or
     * {@code @yearly}, {@code @annually}, {@code @monthly}, {@code @weekly}, {@code @daily}, {@code @midnight},
     * {@code @hourly}. Optional fields left {@code null} are not sent: {@code scheduleTimezone} ({@code UTC} by
     * default, or {@code Region/City}), {@code catchupPolicy} ({@code skip} by default, {@code run_once},
     * {@code run_all}), {@code collection}, {@code enabled} (default true), {@code logOkInvocations} (default false).
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record CreateInput(
        @JsonProperty("functionName") String functionName,
        @JsonProperty("scheduleExpression") String scheduleExpression,
        @JsonProperty("scheduleTimezone") String scheduleTimezone,
        @JsonProperty("catchupPolicy") String catchupPolicy,
        @JsonProperty("collection") String collection,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("logOkInvocations") Boolean logOkInvocations
    ) {
        public CreateInput(String functionName, String scheduleExpression) {
            this(functionName, scheduleExpression, null, null, null, null, null);
        }
    }

    /**
     * Fields to change in {@link #update}; at least one must be set. The function cannot be changed.
     * {@code status} is {@code active} or {@code paused}. Fields left {@code null} are not sent.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record UpdateInput(
        @JsonProperty("scheduleExpression") String scheduleExpression,
        @JsonProperty("scheduleTimezone") String scheduleTimezone,
        @JsonProperty("catchupPolicy") String catchupPolicy,
        @JsonProperty("status") String status,
        @JsonProperty("enabled") Boolean enabled,
        @JsonProperty("logOkInvocations") Boolean logOkInvocations
    ) {
        boolean isEmpty() {
            return scheduleExpression == null && scheduleTimezone == null && catchupPolicy == null
                && status == null && enabled == null && logOkInvocations == null;
        }
    }

    /** List schedules, newest first, first page. */
    public Envelope<List<Schedule>> list() {
        return list(null, null, null, null);
    }

    /**
     * List schedules, newest first, one page at a time. All arguments are optional ({@code null} is not sent):
     * {@code status} ({@code active} or {@code paused}), {@code functionName}, {@code limit} (1 to 200, gateway
     * default 50) and {@code cursor} ({@code meta().cursor().next()} of the previous page).
     */
    public Envelope<List<Schedule>> list(String status, String functionName, Integer limit, String cursor) {
        checkLimit(limit);
        Map<String, String> q = new LinkedHashMap<>();
        if (status != null) q.put("status", status);
        if (functionName != null) q.put("function_name", functionName);
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (cursor != null) q.put("cursor", cursor);
        return HttpTransport.request(config, "GET", path(""), null, q,
            new TypeReference<Envelope<List<Schedule>>>() {});
    }

    public Envelope<Schedule> get(String id) {
        return HttpTransport.request(config, "GET", one(id, ""), null, null,
            new TypeReference<Envelope<Schedule>>() {});
    }

    /** Create a schedule. Answers 201 with the schedule. */
    public Envelope<Schedule> create(CreateInput input) {
        if (input == null) throw invalid("input must be an object", Map.of());
        return HttpTransport.request(config, "POST", path(""), input, null,
            new TypeReference<Envelope<Schedule>>() {});
    }

    /** Change a schedule. Set at least one field; {@code status} {@code paused} stops it, {@code active} resumes it. */
    public Envelope<Schedule> update(String id, UpdateInput patch) {
        if (patch == null || patch.isEmpty()) {
            throw invalid("patch needs at least one of: scheduleExpression, scheduleTimezone, catchupPolicy, status, enabled, logOkInvocations", Map.of());
        }
        return HttpTransport.request(config, "PATCH", one(id, ""), patch, null,
            new TypeReference<Envelope<Schedule>>() {});
    }

    /** Delete a schedule. Answers 204 with no body, so {@code data()} is {@code null}. */
    public Envelope<Void> delete(String id) {
        return HttpTransport.request(config, "DELETE", one(id, ""), null, null,
            new TypeReference<Envelope<Void>>() {});
    }

    /** The schedule's runs, newest first, first page. */
    public Envelope<List<Run>> runs(String id) {
        return runs(id, null, null);
    }

    /** The schedule's runs, newest first, one page at a time. {@code limit} is 1 to 200; both arguments may be {@code null}. */
    public Envelope<List<Run>> runs(String id, Integer limit, String cursor) {
        checkLimit(limit);
        Map<String, String> q = new LinkedHashMap<>();
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (cursor != null) q.put("cursor", cursor);
        return HttpTransport.request(config, "GET", one(id, "/runs"), null, q,
            new TypeReference<Envelope<List<Run>>>() {});
    }

    /** Start one run now, outside the timetable. Answers 202; {@code data()} is the JSON the gateway sent. */
    public Envelope<JsonNode> runNow(String id) {
        return HttpTransport.request(config, "POST", one(id, "/run-now"), null, null,
            new TypeReference<Envelope<JsonNode>>() {});
    }
}
