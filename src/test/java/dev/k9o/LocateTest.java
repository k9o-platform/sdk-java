package dev.k9o;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The locate door: the credential flows ask the project which region holds the address, and send the credential only
 * to an origin that passes the rule in {@link Locate}.
 */
class LocateTest {

    static final ObjectMapper M = new ObjectMapper();
    static final String BASE = "https://acme.api-de01.k9o.io";

    // ---- the pure rule ----

    private static String choose(String base, String answerJson) {
        try {
            return Locate.chooseLocatedOrigin(base, answerJson == null ? null : M.readTree(answerJson));
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private static String ans(String region, String origin) {
        return "{\"region\":\"" + region + "\",\"origin\":" + (origin == null ? "null" : "\"" + origin + "\"") + "}";
    }

    @Test
    void usesASiblingRegionOfTheSameOrgFamilyAndApex() {
        assertEquals("https://acme.api-us01.k9o.io", choose(BASE, ans("us01", "https://acme.api-us01.k9o.io")));
    }

    @Test
    void usesTheBasesOwnRegion() {
        assertEquals(BASE, choose(BASE, ans("de01", BASE)));
    }

    @Test
    void usesATrailingSlash() {
        assertEquals("https://acme.api-us01.k9o.io", choose(BASE, ans("us01", "https://acme.api-us01.k9o.io/")));
    }

    @Test
    void fallsBackToTheBase() {
        Map<String, String> refused = new java.util.LinkedHashMap<>();
        refused.put("null origin (single host, self-hosted)", ans("de01", null));
        refused.put("a missing answer", null);
        refused.put("http", ans("us01", "http://acme.api-us01.k9o.io"));
        refused.put("userinfo", ans("us01", "https://u:p@acme.api-us01.k9o.io"));
        refused.put("a port", ans("us01", "https://acme.api-us01.k9o.io:8443"));
        refused.put("a path", ans("us01", "https://acme.api-us01.k9o.io/x"));
        refused.put("a query", ans("us01", "https://acme.api-us01.k9o.io/?a=1"));
        refused.put("a fragment", ans("us01", "https://acme.api-us01.k9o.io/#f"));
        refused.put("another org", ans("us01", "https://evil.api-us01.k9o.io"));
        refused.put("another apex", ans("us01", "https://acme.api-us01.evil.io"));
        refused.put("another family", ans("us01", "https://acme.api-staging-us01.k9o.io"));
        refused.put("a code that differs from region", ans("us01", "https://acme.api-fr01.k9o.io"));
        refused.put("a region that is not a code", ans("usa", "https://acme.api-usa.k9o.io"));
        refused.put("a host with no region", ans("us01", "https://acme.api.k9o.io"));
        refused.put("a region as a subdomain", ans("us01", "https://us01.acme.api-de01.k9o.io"));
        refused.put("a look-alike apex", ans("us01", "https://acme.api-us01.k9o.io.evil.io"));
        refused.put("a non-object answer", "\"https://acme.api-us01.k9o.io\"");
        refused.put("an origin that is not text", "{\"region\":\"us01\",\"origin\":5}");
        refused.put("an origin that does not parse", ans("us01", "https://acme .api-us01.k9o.io"));
        for (var e : refused.entrySet()) {
            assertEquals(BASE, choose(BASE, e.getValue()), e.getKey());
        }
    }

    @Test
    void allowsHttpOnlyWhenTheBaseIsHttpOnLoopbackAndThenOnlyTheSameHost() {
        assertEquals("http://localhost:8080", choose("http://localhost:8080", ans("de01", "http://localhost:8080")));
        assertEquals("http://localhost:8080", choose("http://localhost:8080", ans("de01", "http://evil.localhost:8080")));
        assertEquals("https://localhost", choose("https://localhost", ans("de01", "http://localhost")));
    }

    @Test
    void usesTheBaseWhenTheBaseIsNotAGatewayHostShape() {
        assertEquals("https://gw.internal.example",
            choose("https://gw.internal.example", ans("us01", "https://gw-us01.internal.example")));
    }

    // ---- the flows ----

    record Seen(String method, String url, String body, Map<String, List<String>> headers) {
        boolean hasHeader(String n) {
            return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(n));
        }
    }

    /** Stub transport: answers every request through {@code answer(url)} = {status, json body}. */
    static final class Stub extends HttpClient {
        final List<Seen> seen = new ArrayList<>();
        final Function<String, Object[]> answer;

        Stub(Function<String, Object[]> answer) { this.answer = answer; }

        @Override @SuppressWarnings("unchecked")
        public <T> HttpResponse<T> send(HttpRequest req, HttpResponse.BodyHandler<T> h) {
            String body = req.bodyPublisher().map(p -> {
                var sub = new java.util.concurrent.Flow.Subscriber<java.nio.ByteBuffer>() {
                    final java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
                    public void onSubscribe(java.util.concurrent.Flow.Subscription s) { s.request(Long.MAX_VALUE); }
                    public void onNext(java.nio.ByteBuffer b) { byte[] a = new byte[b.remaining()]; b.get(a); out.writeBytes(a); }
                    public void onError(Throwable t) {}
                    public void onComplete() {}
                };
                p.subscribe(sub);
                return sub.out.toString(java.nio.charset.StandardCharsets.UTF_8);
            }).orElse("");
            String url = req.uri().toString();
            seen.add(new Seen(req.method(), url, body, req.headers().map()));
            Object[] a = answer.apply(url);
            return (HttpResponse<T>) new RegionTest.Resp<Object>(req, (Integer) a[0], Map.of(), (String) a[1]);
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h) { throw new UnsupportedOperationException(); }
        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest r, HttpResponse.BodyHandler<T> h, HttpResponse.PushPromiseHandler<T> p) { throw new UnsupportedOperationException(); }
        @Override public Optional<CookieHandler> cookieHandler() { return Optional.empty(); }
        @Override public Optional<Duration> connectTimeout() { return Optional.empty(); }
        @Override public Redirect followRedirects() { return Redirect.NEVER; }
        @Override public Optional<ProxySelector> proxy() { return Optional.empty(); }
        @Override public SSLContext sslContext() { return null; }
        @Override public SSLParameters sslParameters() { return new SSLParameters(); }
        @Override public Optional<Authenticator> authenticator() { return Optional.empty(); }
        @Override public Version version() { return Version.HTTP_1_1; }
        @Override public Optional<Executor> executor() { return Optional.empty(); }
    }

    static K9o.K9oClient client(Stub s) {
        return K9o.createClient(K9o.Opts.builder().baseUrl(BASE).projectSlug("portal").apiKey("secret-key")
            .httpClient(s).retryConfig(new RetryConfig(0, "none", 0, 0)).build());
    }

    static Object[] ok(String json) { return new Object[] {200, json}; }

    static final String AUTH = "{\"data\":{\"user\":{\"id\":\"eu_1\"},\"session\":{\"accessToken\":\"a\",\"refreshToken\":\"%s\"}},\"meta\":{}}";

    static String door(String region, String origin) {
        return "{\"data\":" + ans(region, origin) + ",\"meta\":{}}";
    }

    @Test
    void signInSendsOnlyTheEmailToTheDoorThenThePasswordToTheLocatedOrigin() throws IOException {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok(door("us01", "https://acme.api-us01.k9o.io")) : ok(AUTH.formatted("r-a")));
        client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(List.of(BASE + "/@portal/gate/locate", "https://acme.api-us01.k9o.io/@portal/gate/signin"),
            s.seen.stream().map(Seen::url).toList());
        assertEquals(M.readTree("{\"email\":\"a@b.co\"}"), M.readTree(s.seen.get(0).body()));
        assertFalse(s.seen.get(0).hasHeader("authorization"));
        assertTrue(s.seen.get(1).hasHeader("authorization"));
    }

    @Test
    void signInNeverSendsThePasswordToAnOriginThatFailsTheRule() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok(door("us01", "https://evil.example")) : ok(AUTH.formatted("r-b")));
        client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(BASE + "/@portal/gate/signin", s.seen.get(1).url());
        assertTrue(s.seen.stream().noneMatch(x -> x.url().contains("evil.example")));
    }

    @Test
    void signInUsesTheBaseWhenTheDoorAnswersOriginNull() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok(door("de01", null)) : ok(AUTH.formatted("r-c")));
        client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(BASE + "/@portal/gate/signin", s.seen.get(1).url());
    }

    @Test
    void signInUsesTheBaseWhenTheDoorAnswersA2xxWithAMalformedBody() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok("not json") : ok(AUTH.formatted("r-d")));
        client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(BASE + "/@portal/gate/signin", s.seen.get(1).url());
    }

    @Test
    void signInSendsNothingWhenTheDoorIsDownOrRateLimitedAndRaisesTheDoorsError() {
        for (Object[] c : new Object[][] {{503, "K9O_GATE_REGION_UNAVAILABLE"}, {429, "K9O_RATE_LIMITED"}}) {
            Stub s = new Stub(u -> new Object[] {c[0],
                "{\"code\":\"" + c[1] + "\",\"message\":\"x\",\"context\":{},\"recommendation\":null}"});
            KError e = assertThrows(KError.class, () -> client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw")));
            assertEquals(c[1], e.getCode());
            assertEquals(c[0], e.getStatus());
            assertEquals(1, s.seen.size());
        }
        assertEquals("K9O_GATE_REGION_UNAVAILABLE", Gate.REGION_UNAVAILABLE);
        assertEquals("K9O_RATE_LIMITED", Gate.RATE_LIMITED);
    }

    static Object[] err(int status, String code) {
        return new Object[] {status, "{\"code\":\"" + code + "\",\"message\":\"x\",\"context\":{},\"recommendation\":null}"};
    }

    @Test
    void signInUsesTheBaseWhenTheDoorAnswers404() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? err(404, "K9O_NOT_FOUND") : ok(AUTH.formatted("r-e")));
        client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(List.of(BASE + "/@portal/gate/locate", BASE + "/@portal/gate/signin"),
            s.seen.stream().map(Seen::url).toList());
    }

    @Test
    void signInStillRaisesWhenTheDoorAnswers429Or503AndSendsNoCredential() {
        for (int status : new int[] {429, 503}) {
            Stub s = new Stub(u -> err(status, status == 429 ? "K9O_RATE_LIMITED" : "K9O_GATE_REGION_UNAVAILABLE"));
            KError e = assertThrows(KError.class, () -> client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw")));
            assertEquals(status, e.getStatus());
            assertEquals(List.of(BASE + "/@portal/gate/locate"), s.seen.stream().map(Seen::url).toList());
        }
    }

    @Test
    void signInDoesNotAbsorbA404FromTheSignInRequestItself() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? err(404, "K9O_NOT_FOUND") : err(404, "K9O_GATE_USER_NOT_FOUND"));
        KError e = assertThrows(KError.class, () -> client(s).gate().signIn(new Gate.SignInInput("a@b.co", "pw")));
        assertEquals("K9O_GATE_USER_NOT_FOUND", e.getCode());
        assertEquals(404, e.getStatus());
        assertEquals(List.of(BASE + "/@portal/gate/locate", BASE + "/@portal/gate/signin"),
            s.seen.stream().map(Seen::url).toList());
    }

    @Test
    void doesNotCacheTheLocatedOriginTheNextSignInAsksAgain() {
        int[] n = {0};
        Stub s = new Stub(u -> {
            if (!u.endsWith("/gate/locate")) return ok(AUTH.formatted("r-e" + n[0]));
            return n[0]++ == 0 ? ok(door("us01", "https://acme.api-us01.k9o.io")) : ok(door("fr01", "https://acme.api-fr01.k9o.io"));
        });
        var g = client(s).gate();
        g.signIn(new Gate.SignInInput("a@b.co", "pw"));
        g.signIn(new Gate.SignInInput("a@b.co", "pw"));
        assertEquals(2, s.seen.stream().filter(x -> x.url().endsWith("/gate/locate")).count());
        assertEquals(List.of("https://acme.api-us01.k9o.io/@portal/gate/signin", "https://acme.api-fr01.k9o.io/@portal/gate/signin"),
            s.seen.stream().map(Seen::url).filter(u -> u.endsWith("/signin")).toList());
    }

    @Test
    void refreshAndSignOutGoToTheOriginThatIssuedTheSessionAndTheHintIsForgotten() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok(door("us01", "https://acme.api-us01.k9o.io"))
            : ok(u.endsWith("/signin") ? AUTH.formatted("r-1") : "{\"data\":{\"revoked\":true},\"meta\":{}}"));
        var g = client(s).gate();
        g.signIn(new Gate.SignInInput("a@b.co", "pw"));
        g.refreshToken(new Gate.RefreshTokenInput("r-1"));
        g.signOut(new Gate.SignOutInput("r-1"));
        List<String> urls = s.seen.stream().map(Seen::url).toList();
        assertTrue(urls.contains("https://acme.api-us01.k9o.io/@portal/gate/refresh"));
        assertTrue(urls.contains("https://acme.api-us01.k9o.io/@portal/gate/signout"));
        g.refreshToken(new Gate.RefreshTokenInput("r-1"));
        assertEquals(BASE + "/@portal/gate/refresh", s.seen.get(s.seen.size() - 1).url());
    }

    @Test
    void refreshRotatesTheRefreshTokenAndTheHintFollowsIt() {
        Stub s = new Stub(u -> u.endsWith("/gate/locate") ? ok(door("us01", "https://acme.api-us01.k9o.io"))
            : ok(u.endsWith("/signin") ? AUTH.formatted("r-1")
                : "{\"data\":{\"accessToken\":\"a2\",\"refreshToken\":\"r-2\",\"expiresAt\":\"x\"},\"meta\":{}}"));
        var g = client(s).gate();
        g.signIn(new Gate.SignInInput("a@b.co", "pw"));
        var out = g.refreshToken(new Gate.RefreshTokenInput("r-1"));
        assertEquals("r-2", out.data().path("refreshToken").asText());
        g.refreshToken(new Gate.RefreshTokenInput("r-2"));
        assertEquals(List.of("https://acme.api-us01.k9o.io/@portal/gate/refresh", "https://acme.api-us01.k9o.io/@portal/gate/refresh"),
            s.seen.stream().map(Seen::url).filter(u -> u.endsWith("/refresh")).toList());
        g.signOut(new Gate.SignOutInput("r-1")); // the old secret has no hint any more
        assertEquals(BASE + "/@portal/gate/signout", s.seen.get(s.seen.size() - 1).url());
    }

    @Test
    void aRefusedRefreshCarriesTheSessionExpiredReason() {
        for (String reason : new String[] {"replay", "revoked", "inactive", "max_length"}) {
            Stub s = new Stub(u -> new Object[] {401, "{\"code\":\"K9O_SESSION_EXPIRED\",\"message\":\"The session has ended.\",\"context\":{\"reason\":\"" + reason + "\"}}"});
            KError e = assertThrows(KError.class, () -> client(s).gate().refreshToken(new Gate.RefreshTokenInput("r-1")));
            assertEquals("K9O_SESSION_EXPIRED", e.getCode());
            assertEquals(401, e.getStatus());
            assertEquals(reason, e.getContext().get("reason"));
        }
    }

    @Test
    void revokeAllSessionsPostsWithTheBearerAndReturnsTheCount() {
        Stub s = new Stub(u -> ok("{\"data\":{\"revokedCount\":3},\"meta\":{}}"));
        var out = client(s).gate().revokeAllSessions();
        assertEquals(3, out.data().path("revokedCount").asInt());
        assertEquals(1, s.seen.size());
        assertEquals("POST", s.seen.get(0).method());
        assertEquals(BASE + "/@portal/auth/sessions/revoke-all", s.seen.get(0).url());
        assertEquals("", s.seen.get(0).body());
        assertEquals(List.of("Bearer secret-key"), s.seen.get(0).headers().get("authorization"));
    }

    @Test
    void revokeAllSessionsRefusalsAreKErrors() {
        for (String code : new String[] {"K9O_TRUST_ZONE_VIOLATION", "K9O_PROJECT_SCOPE_MISMATCH"}) {
            Stub s = new Stub(u -> new Object[] {403, "{\"code\":\"" + code + "\",\"message\":\"refused\",\"context\":{}}"});
            KError e = assertThrows(KError.class, () -> client(s).gate().revokeAllSessions());
            assertEquals(code, e.getCode());
            assertEquals(403, e.getStatus());
        }
    }

    @Test
    void theHintMapIsBounded() {
        for (int i = 0; i < 200; i++) Locate.rememberSessionOrigin("bound-" + i, "https://x");
        assertTrue(Locate.hintCount() <= 64);
        assertNull(Locate.sessionOriginHint("bound-0"));
        assertEquals("https://x", Locate.sessionOriginHint("bound-199"));
    }

    @Test
    void signUpAndWhoamiDoNotUseTheDoor() {
        Stub s = new Stub(u -> ok(AUTH.formatted("r-f")));
        var g = client(s).gate();
        g.signUp(new Gate.SignUpInput("a@b.co", "pw-long-enough-1", null));
        g.whoami();
        assertTrue(s.seen.stream().noneMatch(x -> x.url().endsWith("/locate")));
    }
}
