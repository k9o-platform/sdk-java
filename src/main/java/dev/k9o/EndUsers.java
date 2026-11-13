package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * End users, the people who sign in to the project's own apps: {@code client.endUsers()}.
 *
 * <p>Wire (project plane):
 * <ul>
 * <li>{@code list(status, q, limit, cursor)}: {@code GET /@<slug>/end-users}</li>
 * <li>{@code get(id)}: {@code GET /@<slug>/end-users/<id>}</li>
 * <li>{@code roleGrants().list(endUserId)}: {@code GET /@<slug>/end-user-role-grants}</li>
 * <li>{@code roleGrants().grant(endUserId, role)}: {@code POST /@<slug>/end-user-role-grants}</li>
 * <li>{@code roleGrants().revoke(grantId)}: {@code DELETE /@<slug>/end-user-role-grants/<grantId>}</li>
 * </ul>
 *
 * <p>Listing and reading end users needs the project admin role. Role grants are created and revoked by project
 * admins; reading them is open to a wider set of project roles.
 */
public final class EndUsers {

    private final HttpTransport.Config config;
    private final RoleGrants roleGrants;

    EndUsers(HttpTransport.Config config) {
        this.config = config;
        this.roleGrants = new RoleGrants();
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static KError invalid(String message) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, Map.of(), null, null, null);
    }

    private static String requireText(String value, String what, int max) {
        if (value == null || value.isEmpty() || value.length() > max) {
            throw invalid(what + " must be a string of 1 to " + max + " characters");
        }
        return value;
    }

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "endUsers operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, tail);
    }

    /** An end user. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EndUser(
        /** {@code eu_<base62>}. */
        @JsonProperty("id") String id,
        @JsonProperty("email") String email,
        @JsonProperty("emailVerifiedAt") String emailVerifiedAt,
        /** {@code active}, {@code locked} or {@code deleted}. */
        @JsonProperty("status") String status,
        @JsonProperty("createdAt") String createdAt,
        @JsonProperty("lastSigninAt") String lastSigninAt,
        @JsonProperty("signInMethods") List<String> signInMethods,
        @JsonProperty("region") String region
    ) {}

    /** A role held by one end user. The role is a free string, not a fixed list. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RoleGrant(
        /** {@code eurg_<base62>}. */
        @JsonProperty("id") String id,
        @JsonProperty("endUserId") String endUserId,
        @JsonProperty("role") String role,
        @JsonProperty("grantedAt") String grantedAt,
        /** The subject that granted the role. */
        @JsonProperty("grantedBy") String grantedBy
    ) {}

    /** Answer of {@link RoleGrants#revoke}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RevokeResult(
        /** {@code false} when the grant does not exist or was already revoked. */
        @JsonProperty("revoked") boolean revoked,
        @JsonProperty("revokedAt") String revokedAt
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrantsWrapper(@JsonProperty("grants") List<RoleGrant> grants) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record GrantWrapper(@JsonProperty("grant") RoleGrant grant) {}

    /** List end users, newest first, first page (active and locked). */
    public Envelope<List<EndUser>> list() {
        return list(null, null, null, null);
    }

    /**
     * List end users, newest first, one page at a time. All arguments are optional ({@code null} is not sent):
     * {@code status} ({@code active}, {@code locked}, {@code deleted} or {@code all}; default active and locked),
     * {@code q} (an email prefix, case-sensitive, up to 320 characters), {@code limit} (1 to 200, default 50) and
     * {@code cursor} ({@code meta().cursor().next()} of the previous page).
     */
    public Envelope<List<EndUser>> list(String status, String q, Integer limit, String cursor) {
        Map<String, String> query = new LinkedHashMap<>();
        if (status != null) query.put("status", status);
        if (q != null) query.put("q", q);
        if (limit != null) query.put("limit", String.valueOf(limit));
        if (cursor != null) query.put("cursor", cursor);
        return HttpTransport.request(config, "GET", path("end-users"), null, query,
            new TypeReference<Envelope<List<EndUser>>>() {});
    }

    /** One end user. An unknown id is a 404 {@code K9O_END_USER_NOT_FOUND}. */
    public Envelope<EndUser> get(String id) {
        return HttpTransport.request(config, "GET", path("end-users/" + enc(requireText(id, "end user id", 64))), null, null,
            new TypeReference<Envelope<EndUser>>() {});
    }

    /** The roles end users hold. */
    public RoleGrants roleGrants() { return roleGrants; }

    /** Role grants, {@code client.endUsers().roleGrants()}. */
    public final class RoleGrants {

        /** All active grants, oldest first. */
        public Envelope<List<RoleGrant>> list() {
            return list(null);
        }

        /**
         * The active grants, oldest first, not paginated; {@code endUserId} ({@code null} for all) narrows to one
         * end user. An unknown id answers an empty list. Returns the list, not the {@code {grants}} wrapper the
         * gateway sends.
         */
        public Envelope<List<RoleGrant>> list(String endUserId) {
            Map<String, String> q = new LinkedHashMap<>();
            if (endUserId != null) q.put("endUserId", endUserId);
            Envelope<GrantsWrapper> r = HttpTransport.request(config, "GET", path("end-user-role-grants"), null, q,
                new TypeReference<Envelope<GrantsWrapper>>() {});
            return new Envelope<>(r.data().grants(), r.meta());
        }

        /**
         * Give an end user a role. {@code endUserId} is up to 64 characters; {@code role} is any string of 1 to 128
         * characters. Granting a role the user already holds is a 400 {@code K9O_INVALID_FIELD_VALUE}; an unknown
         * end user is a 404 {@code K9O_NOT_FOUND}. A revoked role can be granted again.
         */
        public Envelope<RoleGrant> grant(String endUserId, String role) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("endUserId", requireText(endUserId, "endUserId", 64));
            body.put("role", requireText(role, "role", 128));
            Envelope<GrantWrapper> r = HttpTransport.request(config, "POST", path("end-user-role-grants"), body, null,
                new TypeReference<Envelope<GrantWrapper>>() {});
            return new Envelope<>(r.data().grant(), r.meta());
        }

        /**
         * Revoke a grant (it is kept, marked revoked). An unknown or already revoked id is not an error: the
         * answer is {@code revoked} false with a {@code null} {@code revokedAt}.
         */
        public Envelope<RevokeResult> revoke(String grantId) {
            return HttpTransport.request(config, "DELETE", path("end-user-role-grants/" + enc(requireText(grantId, "grant id", 256))), null, null,
                new TypeReference<Envelope<RevokeResult>>() {});
        }
    }
}
