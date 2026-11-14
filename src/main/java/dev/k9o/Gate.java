package dev.k9o;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.util.Map;

/**
 * Gate (Identity-Aware Kernel) namespace — {@code client.gate().*}.
 * Project-scoped — requires {@code projectSlug} bound on
 * the client; throws {@link KError K9O_MISSING_PROJECT_CONTEXT} otherwise.
 */
public class Gate {

    private final HttpTransport.Config config;

    Gate(HttpTransport.Config config) {
        this.config = config;
    }

    public record SignInInput(
        @JsonProperty("email") String email,
        @JsonProperty("password") String password
    ) {}

    @com.fasterxml.jackson.annotation.JsonInclude(com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL)
    public record SignUpInput(
        @JsonProperty("email") String email,
        @JsonProperty("password") String password,
        @JsonProperty("displayName") String displayName
    ) {}

    public record SignOutInput(
        @JsonProperty("refreshToken") String refreshToken
    ) {}

    public record RefreshTokenInput(
        @JsonProperty("refreshToken") String refreshToken
    ) {}

    private String requireProject() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError(
                "K9O_MISSING_PROJECT_CONTEXT",
                "client.gate operations require projectSlug bound on the client",
                Map.of(), null, null, null
            );
        }
        return config.projectSlug;
    }

    public Envelope<JsonNode> signUp(SignUpInput input) {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "gate/signup"),
            input, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    /** Gateway code of the locate door when the region that holds the address cannot answer (503, retryable). */
    public static final String REGION_UNAVAILABLE = "K9O_GATE_REGION_UNAVAILABLE";
    /** Gateway code of a 429 from the locate door or any other call. */
    public static final String RATE_LIMITED = "K9O_RATE_LIMITED";

    private static final JavaType ENVELOPE_JSON =
        TypeFactory.defaultInstance().constructParametricType(Envelope.class, JsonNode.class);

    private Envelope<JsonNode> post(String sub, Object input, String origin) {
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(requireProject(), sub),
            input, null, null, false, origin, ENVELOPE_JSON);
    }

    private static String refreshTokenOf(Envelope<JsonNode> env) {
        JsonNode d = env == null ? null : env.data();
        JsonNode t = d == null ? null : d.path("session").path("refreshToken");
        return t != null && t.isTextual() ? t.asText() : null;
    }

    /**
     * Password sign-in. First asks the project which region holds the email (one extra request carrying only the
     * email, no credential), then sends the password only to an origin that passes the host rule (a region host of
     * your own org and apex; otherwise the base URL). A 429 or 503 from that first step is raised as the usual
     * {@link KError} and the password is not sent; it is safe to retry.
     */
    public Envelope<JsonNode> signIn(SignInInput input) {
        String slug = requireProject();
        String origin = Locate.locateOrigin(config, slug, input.email());
        Envelope<JsonNode> out = post("gate/signin", input, origin);
        Locate.rememberSessionOrigin(refreshTokenOf(out), origin);
        return out;
    }

    public Envelope<JsonNode> signOut(SignOutInput input) {
        String origin = Locate.sessionOriginHint(input.refreshToken());
        Envelope<JsonNode> out = post("gate/signout", input, origin);
        Locate.forgetSessionOrigin(input.refreshToken());
        return out;
    }

    public Envelope<JsonNode> whoami() {
        return HttpTransport.request(config, "GET",
            UrlGrammar.projectPath(requireProject(), "gate/whoami"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    /**
     * End every live session of the signed-in end user, on every device, this one included
     * ({@code POST /@<project>/auth/sessions/revoke-all}). Call it with the end user's access token as the client's
     * bearer token (the token {@link #whoami()} uses), not a refresh token. The answer's {@code data} is
     * {@code {revokedCount}}. Each ended session's next refresh throws {@link KError} with code
     * {@code K9O_SESSION_EXPIRED} and a {@code reason} of {@code "revoked"} in its context; access tokens already issued
     * keep working until they expire. An operator's token throws {@code K9O_TRUST_ZONE_VIOLATION} (403), and a token
     * issued for another project {@code K9O_PROJECT_SCOPE_MISMATCH} (403).
     */
    public Envelope<JsonNode> revokeAllSessions() {
        return HttpTransport.request(config, "POST",
            UrlGrammar.projectPath(requireProject(), "auth/sessions/revoke-all"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
    }

    /**
     * Exchange the refresh token for a new access token and a new refresh token. The answer's {@code data} is
     * {@code {accessToken, refreshToken, expiresAt}}. The refresh token you sent stops working: store the returned
     * {@code refreshToken} before the next refresh, and refresh one at a time per session. A second call with a secret
     * that was already used is a replay: the session is revoked and the call throws {@link KError} with code
     * {@code K9O_SESSION_EXPIRED} and a {@code reason} of {@code "replay"} in its context. The newer secret then gets
     * {@code "revoked"}. The other reasons are {@code "inactive"} and {@code "max_length"}; sign in again.
     */
    public Envelope<JsonNode> refreshToken(RefreshTokenInput input) {
        Envelope<JsonNode> out = post("gate/refresh", input, Locate.sessionOriginHint(input.refreshToken()));
        Locate.rotateSessionOrigin(input.refreshToken(), out == null ? null : out.data());
        return out;
    }
}
