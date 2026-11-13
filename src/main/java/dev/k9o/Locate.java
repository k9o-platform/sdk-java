package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.type.TypeFactory;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Locating the end user's region for the credential flows.
 *
 * <p>An end user can live in another region of the project, so password sign-in first asks the project which region
 * holds the address ({@code POST /@<projectSlug>/gate/locate} with only the email, at the client's regional base, no
 * credential) and sends the credential to the origin it names. The answer is the same for a known and an unknown
 * address.
 *
 * <ul>
 *   <li>The located origin is used for that one attempt. It is never cached as authority. The one thing kept is a
 *       hint per refresh token, so refresh and sign-out reach the region that issued the session; a stale hint gets
 *       a 401.</li>
 *   <li>An origin is used only if it is https ({@code http} only when the base is itself http on a loopback host and
 *       the origin is loopback too), has no userinfo, port, path, query or fragment, and its host is exactly the base
 *       host's org, gateway family and apex with the region code {@code [a-z]{2}\d{2}} added or swapped in the service
 *       label, and that code equals the answer's {@code region}. Anything else, or {@code origin: null}, means the
 *       base is used. A password never goes to a host that fails this.</li>
 * </ul>
 */
final class Locate {

    private Locate() {}

    private static final Pattern REGION_CODE = Pattern.compile("^[a-z]{2}\\d{2}$");
    private static final int HINT_LIMIT = 64;

    private static boolean isLoopback(String host) {
        if (host == null) return false;
        String h = host.toLowerCase(Locale.ROOT);
        return h.equals("localhost") || h.endsWith(".localhost") || h.equals("127.0.0.1") || h.equals("[::1]") || h.equals("::1");
    }

    /** The origin the credential goes to: the located one if it passes the rule, else {@code base}. Never throws. */
    static String chooseLocatedOrigin(String base, JsonNode answer) {
        try {
            if (answer == null || !answer.isObject()) return base;
            JsonNode regionNode = answer.get("region");
            JsonNode originNode = answer.get("origin");
            if (originNode == null || !originNode.isTextual() || regionNode == null || !regionNode.isTextual()) return base;
            String region = regionNode.asText();
            if (!REGION_CODE.matcher(region).matches()) return base;
            URI b = URI.create(base);
            URI u = URI.create(originNode.asText());
            String uScheme = u.getScheme() == null ? null : u.getScheme().toLowerCase(Locale.ROOT);
            String bScheme = b.getScheme() == null ? null : b.getScheme().toLowerCase(Locale.ROOT);
            String uHost = u.getHost() == null ? null : u.getHost().toLowerCase(Locale.ROOT);
            String bHost = b.getHost() == null ? null : b.getHost().toLowerCase(Locale.ROOT);
            if (uScheme == null || bScheme == null || uHost == null || bHost == null) return base;
            if (u.getRawUserInfo() != null || u.getPort() != -1 || u.getRawQuery() != null || u.getRawFragment() != null) return base;
            String path = u.getRawPath();
            if (path != null && !path.isEmpty() && !path.equals("/")) return base;
            boolean https = uScheme.equals("https");
            boolean looseHttp = uScheme.equals("http") && bScheme.equals("http") && isLoopback(bHost) && isLoopback(uHost);
            if (!https && !looseHttp) return base;
            if (uScheme.equals(bScheme) && uHost.equals(bHost) && b.getPort() == -1) return bScheme + "://" + bHost;
            UrlGrammar.ApiHost from = UrlGrammar.parseApiHost(bHost);
            UrlGrammar.ApiHost to = UrlGrammar.parseApiHost(uHost);
            if (from == null || to == null || !region.equals(to.region())) return base;
            if (!Objects.equals(from.orgSlug(), to.orgSlug()) || !Objects.equals(from.family(), to.family())
                || !Objects.equals(from.apex(), to.apex())) return base;
            return uScheme + "://" + uHost;
        } catch (RuntimeException e) {
            return base;
        }
    }

    /**
     * Ask the project which region holds {@code email} and return the origin the credential goes to. A 404 from the
     * door means the locate door is off and the base is used. Any other non-2xx answer from the door is raised as the usual {@link KError} and nothing is retried; a 2xx with a malformed body means
     * the base.
     */
    static String locateOrigin(HttpTransport.Config config, String projectSlug, String email) {
        String base = HttpTransport.resolveRegionalOrigin(config);
        Envelope<JsonNode> env;
        try {
            env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(projectSlug, "gate/locate"),
                Map.of("email", email == null ? "" : email), null, null, true,
                TypeFactory.defaultInstance().constructParametricType(Envelope.class, JsonNode.class));
        } catch (KError e) {
            Integer st = e.getStatus();
            if (st != null && st == 404) return base;
            if ("K9O_INTERNAL_ERROR".equals(e.getCode()) && st != null && st >= 200 && st < 300) return base;
            throw e;
        }
        return chooseLocatedOrigin(base, env == null ? null : env.data());
    }

    // ---- session origin hints (refresh token -> origin) ----

    private static final Map<String, String> HINTS = new LinkedHashMap<>();

    /** Remember which origin issued the session with this refresh token (a hint, not authority). */
    static void rememberSessionOrigin(String refreshToken, String origin) {
        if (refreshToken == null || refreshToken.isEmpty()) return;
        synchronized (HINTS) {
            HINTS.remove(refreshToken);
            HINTS.put(refreshToken, origin);
            while (HINTS.size() > HINT_LIMIT) HINTS.remove(HINTS.keySet().iterator().next());
        }
    }

    /** The hint for this refresh token, or null. */
    static String sessionOriginHint(String refreshToken) {
        if (refreshToken == null) return null;
        synchronized (HINTS) {
            return HINTS.get(refreshToken);
        }
    }

    static void forgetSessionOrigin(String refreshToken) {
        if (refreshToken == null) return;
        synchronized (HINTS) {
            HINTS.remove(refreshToken);
        }
    }

    /**
     * A refresh answers with a new refresh token and the old one stops working: the hint follows the session. Data
     * without a new token changes nothing.
     */
    static void rotateSessionOrigin(String oldRefreshToken, JsonNode data) {
        JsonNode t = data == null ? null : data.path("refreshToken");
        String origin = sessionOriginHint(oldRefreshToken);
        if (t == null || !t.isTextual() || t.asText().isEmpty() || origin == null) return;
        forgetSessionOrigin(oldRefreshToken);
        rememberSessionOrigin(t.asText(), origin);
    }

    /** Test hook: size of the hint map. */
    static int hintCount() {
        synchronized (HINTS) {
            return HINTS.size();
        }
    }
}
