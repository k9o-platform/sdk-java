package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pending-change queue namespace: {@code client.approvals()}.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code list(status, limit, cursor)}: {@code GET /@<slug>/pending-approvals}</li>
 * <li>{@code get(id)}: {@code GET /@<slug>/pending-approvals/<id>}</li>
 * <li>{@code approve(id, reason)}: {@code POST /@<slug>/pending-approvals/<id>/approve}</li>
 * <li>{@code reject(id, reason)}: {@code POST /@<slug>/pending-approvals/<id>/reject}</li>
 * <li>{@code bulk(action, criteria, reason)}: {@code POST /@<slug>/pending-approvals/bulk}</li>
 * <li>{@code comments().add(pendingId, body, visibility)}: {@code POST /@<slug>/pending-approvals/<id>/comments}</li>
 * <li>{@code comments().list(pendingId, limit, cursor)}: {@code GET /@<slug>/pending-approvals/<id>/comments}</li>
 * </ul>
 *
 * <p>{@code list} is cursor-paginated, newest first: a page holds up to {@code limit} changes (1 to 200, default 50);
 * pass {@code meta().cursor().next()} of one page as {@code cursor} to read the next, until it is {@code null}.
 * A cursor belongs to the {@code status} it was issued for. Project admins see the whole queue; other members see
 * only the changes they proposed.
 */
public final class Approvals {

    private final HttpTransport.Config config;

    private final Comments comments;

    Approvals(HttpTransport.Config config) {
        this.config = config;
        this.comments = new Comments();
    }

    /** Comments on a pending change: {@code client.approvals().comments()}. */
    public Comments comments() { return comments; }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "approvals operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "pending-approvals" + tail);
    }

    private static String requireId(String id) {
        if (id == null || id.isEmpty()) throw invalid("approval id must be a non-empty string", Map.of());
        return id;
    }

    static String requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty()) {
            throw invalid("reason is required and must be a non-empty string", Map.of());
        }
        return reason;
    }

    static Map<String, Object> requirePredicate(Map<String, ?> value, String field) {
        if (value == null) throw invalid(field + " must be a predicate object", Map.of());
        return new LinkedHashMap<String, Object>(value);
    }

    /** A change waiting for (or past) a decision. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record PendingChange(
        @JsonProperty("id") String id,
        /** {@code pending_approval}, {@code approved}, {@code rejected}, {@code expired} or {@code redeemed}. */
        @JsonProperty("status") String status,
        @JsonProperty("collection") String collection,
        /** {@code insert}, {@code update} or {@code delete}. */
        @JsonProperty("operation") String operation,
        @JsonProperty("makerId") String makerId,
        @JsonProperty("proposedPayload") Map<String, Object> proposedPayload,
        @JsonProperty("targetRecordId") String targetRecordId,
        @JsonProperty("approverId") String approverId,
        @JsonProperty("decidedAt") String decidedAt,
        @JsonProperty("decisionReason") String decisionReason,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("expiresAt") String expiresAt
    ) {}

    /**
     * What a write answers with when it was queued instead of applied (HTTP 202): the change as it was proposed.
     * The decision fields ({@code approverId}, {@code decidedAt}, {@code decisionReason}, {@code createdAt},
     * {@code expiresAt}) are not part of this answer; read the full change with {@link #get}.
     * {@code proposedPayload} is the record for an insert, the patch for an update and {@code {}} for a delete;
     * {@code targetRecordId} is {@code null} for an insert.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record QueuedChange(
        /** {@code apr_<base62>}; pass it to {@link #get}, {@link #approve} or {@link #reject}. */
        @JsonProperty("id") String id,
        /** Always {@code pending_approval}. */
        @JsonProperty("status") String status,
        @JsonProperty("collection") String collection,
        /** {@code insert}, {@code update} or {@code delete}. */
        @JsonProperty("operation") String operation,
        @JsonProperty("makerId") String makerId,
        @JsonProperty("proposedPayload") Map<String, Object> proposedPayload,
        @JsonProperty("targetRecordId") String targetRecordId
    ) {}

    /** A row a bulk call could not decide. {@code code} is an open set of error codes; {@code reason} is the error message. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkFailure(
        @JsonProperty("id") String id,
        @JsonProperty("code") String code,
        @JsonProperty("reason") String reason
    ) {}

    /**
     * Answer of {@link #bulk}. The call answers HTTP 200 even when rows fail: every matched row is in either
     * {@code affectedIds} or {@code failed}, so compare {@code affectedIds} with the set you meant to decide.
     * Rows already decided are not rolled back. {@code failed} is empty when nothing failed.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record BulkResult(
        @JsonProperty("attestationId") String attestationId,
        @JsonProperty("action") String action,
        @JsonProperty("affectedCount") int affectedCount,
        @JsonProperty("affectedIds") List<String> affectedIds,
        @JsonProperty("failed") List<BulkFailure> failed,
        @JsonProperty("reason") String reason
    ) {
        public BulkResult {
            if (failed == null) failed = List.of();
        }
    }

    /** Who may read a comment: {@code everyone} or {@code approvers_only}. Fixed when the comment is posted. */
    public static final String VISIBILITY_EVERYONE = "everyone";
    public static final String VISIBILITY_APPROVERS_ONLY = "approvers_only";

    /** Longest comment body, in characters (Unicode code points). */
    public static final int COMMENT_BODY_MAX = 4000;

    /**
     * A comment on a pending change. A comment is never a decision: it changes no status and counts toward no
     * approval rule. {@code body} is plain text; render it as text, never as HTML.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Comment(
        /** {@code acm_<id>}. */
        @JsonProperty("id") String id,
        /** The pending change the comment is on. */
        @JsonProperty("caseId") String caseId,
        /** The author's opaque user id. */
        @JsonProperty("authorId") String authorId,
        /** {@code everyone} or {@code approvers_only}. */
        @JsonProperty("visibility") String visibility,
        @JsonProperty("body") String body,
        /** RFC 3339 date-time. */
        @JsonProperty("createdAt") String createdAt
    ) {}

    /**
     * One page of comments, oldest first: {@link #data()} holds the comments the caller may see, and
     * {@link #nextCursor()} is the cursor of the next page, {@code null} on the last one.
     */
    public static final class CommentPage extends Envelope<List<Comment>> {
        private final String nextCursor;

        @com.fasterxml.jackson.annotation.JsonCreator
        public CommentPage(
            @JsonProperty("data") List<Comment> data,
            @JsonProperty("meta") Meta meta,
            @JsonProperty("nextCursor") String nextCursor
        ) {
            super(data == null ? List.of() : data, meta);
            this.nextCursor = nextCursor;
        }

        /** Pass as {@code cursor} to read the next page; {@code null} on the last page. */
        public String nextCursor() { return nextCursor; }
    }

    /**
     * Comments on a pending change. Posting needs a session; a personal access token alone is refused. There is no
     * edit and no delete: a correction is a new comment.
     *
     * <p>Errors: {@code K9O_APPROVAL_COMMENT_NOT_ELIGIBLE} (403), {@code K9O_APPROVAL_COMMENT_CLOSED} (409),
     * {@code K9O_INVALID_FIELD_VALUE} (400), {@code K9O_NOT_FOUND} (404) and {@code K9O_RBAC_DENY} (403).
     */
    public final class Comments {
        private Comments() {}

        /**
         * Add a comment to a pending change. {@code body} is plain text of 1 to 4000 characters; {@code visibility}
         * is {@code everyone} or {@code approvers_only} and cannot change afterwards. Someone eligible to decide the
         * change may post either kind; the person who asked for it may post {@code everyone} only
         * ({@code K9O_APPROVAL_COMMENT_NOT_ELIGIBLE} otherwise). A decided, expired or cancelled change refuses
         * with {@code K9O_APPROVAL_COMMENT_CLOSED}. Answers 201 with the comment.
         */
        public Envelope<Comment> add(String pendingId, String body, String visibility) {
            String p = path("/" + enc(requireId(pendingId)) + "/comments");
            if (body == null || body.isEmpty() || body.codePointCount(0, body.length()) > COMMENT_BODY_MAX) {
                throw invalid("body must be 1 to " + COMMENT_BODY_MAX + " characters", Map.of("field", "body"));
            }
            if (!VISIBILITY_EVERYONE.equals(visibility) && !VISIBILITY_APPROVERS_ONLY.equals(visibility)) {
                throw invalid("visibility must be \"everyone\" or \"approvers_only\"", Map.of("field", "visibility"));
            }
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("body", body);
            payload.put("visibility", visibility);
            return HttpTransport.request(config, "POST", p, payload, null,
                new TypeReference<Envelope<Comment>>() {});
        }

        /** The first page of comments on a pending change, oldest first. */
        public CommentPage list(String pendingId) {
            return list(pendingId, null, null);
        }

        /**
         * One page of the comments on a pending change the caller may see, oldest first. {@code limit} (optional) is
         * 1 to 200, default 50; {@code cursor} (optional) is {@link CommentPage#nextCursor()} of the previous page and
         * belongs to this {@code pendingId}. A malformed cursor fails with {@code K9O_INVALID_CURSOR} (400).
         */
        public CommentPage list(String pendingId, Integer limit, String cursor) {
            String p = path("/" + enc(requireId(pendingId)) + "/comments");
            if (limit != null && (limit < 1 || limit > 200)) {
                throw invalid("limit must be a whole number from 1 to 200", Map.of("limit", limit));
            }
            Map<String, String> q = new LinkedHashMap<>();
            if (limit != null) q.put("limit", String.valueOf(limit));
            if (cursor != null) q.put("cursor", cursor);
            JavaType t = HttpTransport.JSON.getTypeFactory().constructType(CommentPage.class);
            Envelope<List<Comment>> out = HttpTransport.request(config, "GET", p, null, q, t);
            return (CommentPage) out;
        }
    }

    /** List pending changes, newest first, all statuses. */
    public Envelope<List<PendingChange>> list() {
        return list(null, null);
    }

    /**
     * List pending changes, newest first. {@code status} (optional) is one of {@code pending_approval},
     * {@code approved}, {@code rejected}, {@code expired}, {@code redeemed}. {@code limit} (optional) is 1 to 200.
     * Returns the first page; see {@link #list(String, Integer, String)} to continue.
     */
    public Envelope<List<PendingChange>> list(String status, Integer limit) {
        return list(status, limit, null);
    }

    /**
     * List one page of pending changes, newest first. {@code cursor} (optional) is {@code meta().cursor().next()}
     * of the previous page; keep {@code status} the same. A malformed cursor, or one issued for another status,
     * fails with {@code K9O_INVALID_CURSOR} (HTTP 400).
     */
    public Envelope<List<PendingChange>> list(String status, Integer limit, String cursor) {
        if (limit != null && (limit < 1 || limit > 200)) {
            throw invalid("limit must be a whole number from 1 to 200", Map.of("limit", limit));
        }
        Map<String, String> q = new LinkedHashMap<>();
        if (status != null) q.put("status", status);
        if (limit != null) q.put("limit", String.valueOf(limit));
        if (cursor != null) q.put("cursor", cursor);
        return HttpTransport.request(config, "GET", path(""), null, q,
            new TypeReference<Envelope<List<PendingChange>>>() {});
    }

    /** One pending change. Members who are not queue admins can read only their own. */
    public Envelope<PendingChange> get(String id) {
        return HttpTransport.request(config, "GET", path("/" + enc(requireId(id))), null, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /** Approve a pending change with no note. */
    public Envelope<PendingChange> approve(String id) {
        return approve(id, null);
    }

    /**
     * Approve a pending change; {@code reason} (optional) is stored with the decision. Read {@code status} on the
     * answer: {@code pending_approval} means the approval was recorded but more approvers are needed and the change
     * has not been applied yet.
     */
    public Envelope<PendingChange> approve(String id, String reason) {
        String p = path("/" + enc(requireId(id)) + "/approve");
        Map<String, Object> body = new LinkedHashMap<>();
        if (reason != null) body.put("reason", reason);
        return HttpTransport.request(config, "POST", p, body, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /** Reject a pending change. {@code reason} is required and must be non-empty. */
    public Envelope<PendingChange> reject(String id, String reason) {
        String p = path("/" + enc(requireId(id)) + "/reject");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("reason", requireReason(reason));
        return HttpTransport.request(config, "POST", p, body, null,
            new TypeReference<Envelope<PendingChange>>() {});
    }

    /**
     * Approve or reject every pending change matching {@code criteria}. {@code action} is {@code approve} or
     * {@code reject}; {@code criteria} is a predicate such as {@code {"eq": ["change.table", "orders"]}};
     * {@code reason} is required for {@code reject}. Rows that fail are reported in {@code failed} and the call
     * still answers 200, so compare {@code affectedIds} with what you intended.
     */
    public Envelope<BulkResult> bulk(String action, Map<String, ?> criteria, String reason) {
        if (!"approve".equals(action) && !"reject".equals(action)) {
            throw invalid("action must be \"approve\" or \"reject\"", Map.of());
        }
        Map<String, Object> pred = requirePredicate(criteria, "criteria");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("action", action);
        body.put("criteria", pred);
        if ("reject".equals(action)) body.put("reason", requireReason(reason));
        else if (reason != null) body.put("reason", reason);
        return HttpTransport.request(config, "POST", path("/bulk"), body, null,
            new TypeReference<Envelope<BulkResult>>() {});
    }
}
