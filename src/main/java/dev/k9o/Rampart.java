package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Rampart: hosted classification of content, with a signed receipt: {@code client.rampart()}.
 *
 * <p>Rampart is advisory. A verdict is information; the SDK never blocks anything and the caller decides what to do.
 * When the classifier is down the call fails with a {@link KError} ({@code K9O_RAMPART_CLASSIFIER_UNAVAILABLE}, 503)
 * and there is no verdict, never a default pass. A long input is scored in overlapping windows and the highest
 * injection score decides; past 16 windows (about 6,000 tokens) the call fails with a {@link KError}
 * ({@link #INPUT_TOO_LONG}, 413) and there is no verdict.
 *
 * <p>Wire: {@code classify} is {@code POST /@<slug>/rampart/classify} (any project member); {@code jwks} is
 * {@code GET /rampart/jwks} (public, no project prefix, no credential sent).
 */
public final class Rampart {

    /** The {@code typ} a classification receipt must carry. */
    public static final String RECEIPT_TYP = "k9o.rampart.classify.v1";

    /** The {@link KError#getCode()} of a 413: the input needs more than 16 windows; no verdict is made. */
    public static final String INPUT_TOO_LONG = "K9O_RAMPART_INPUT_TOO_LONG";

    private final HttpTransport.Config config;
    private final Object keyLock = new Object();
    private Jwks cachedFeed;                // the key feed, for the life of the client; replaced on a refetch
    private Map<String, Jwk> cachedKeys;    // kid -> key of cachedFeed

    Rampart(HttpTransport.Config config) {
        this.config = config;
    }

    /**
     * The verdict and the receipt exactly as the gateway sent them. {@code classification} is {@code clear},
     * {@code suspicious} or {@code malicious}; {@code riskScore} and {@code confidence} are integer basis points
     * (0 to 10000). {@code projectId} is the project's id ({@code proj_...}) when the gateway sends it, else {@code null}.
     * Read the verdict from {@link #verifyReceipt} when you need it authenticated: these fields are not.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Classification(String classification, int riskScore, int confidence, List<String> categories,
                                 String calibrationVersion, String classificationReceipt, String projectId) {}

    /** One public key of the key feed. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Jwk(String kid, String kty, String crv, String x, String alg, String use) {}

    /**
     * The key feed: a JWK set and the deployment's {@code issuer} ({@code <issuer>/rampart}), which is {@code null}
     * when the feed does not advertise one.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Jwks(List<Jwk> keys, String issuer) {
        public Jwks {
            keys = keys == null ? List.of() : List.copyOf(keys);
        }

        /** A key set with no advertised issuer. */
        public Jwks(List<Jwk> keys) { this(keys, null); }
    }

    /**
     * The authenticated claims of a receipt that passed every check. {@code windows} is the number of windows the
     * classifier scored (1 when the input fits one window; a receipt without the claim reads as 1). Claims this SDK
     * does not know are ignored.
     */
    public record VerifiedReceipt(String classification, int riskBp, int confidenceBp, List<String> categories,
                                  String projectId, String calibrationVersion, String inputSha256,
                                  String issuer, long issuedAt, long expiresAt, String kid, int windows) {}

    /** Why a receipt was refused. The set is closed. */
    public enum Reason {
        MALFORMED("malformed"), KEY_UNKNOWN("key_unknown"), ALG("alg"), TYP("typ"), SIGNATURE("signature"),
        ISSUER("issuer"), PROJECT("project"), EXPIRED("expired"), NOT_YET_VALID("not_yet_valid"),
        INPUT_HASH("input_hash"), PROJECT_UNKNOWN("project_unknown");

        private final String wire;

        Reason(String wire) { this.wire = wire; }

        /** The lowercase name, for example {@code input_hash}. */
        public String wire() { return wire; }
    }

    /** A receipt that did not verify. There is no partial verdict: nothing of the receipt is returned. */
    public static final class ReceiptRefusal extends RuntimeException {
        private final Reason reason;

        public ReceiptRefusal(Reason reason, String detail) {
            super(reason.wire() + ": " + detail);
            this.reason = reason;
        }

        public Reason reason() { return reason; }
    }

    /** Options of {@link #verifyReceipt(String, Object, VerifyOptions)}; every field is optional. */
    public static final class VerifyOptions {
        final Jwks keys;
        final String issuer;
        final String projectId;
        final long clockSkewSeconds;
        final Clock clock;

        private VerifyOptions(Builder b) {
            this.keys = b.keys;
            this.issuer = b.issuer;
            this.projectId = b.projectId;
            this.clockSkewSeconds = b.clockSkewSeconds;
            this.clock = b.clock;
        }

        public static Builder builder() { return new Builder(); }

        public static final class Builder {
            private Jwks keys;
            private String issuer;
            private String projectId;
            private long clockSkewSeconds = 60;
            private Clock clock = Clock.systemUTC();

            /** A key set you already fetched; no request is made and an unknown {@code kid} is refused. */
            public Builder keys(Jwks v) { this.keys = v; return this; }
            /** The expected {@code iss}. Default: the {@code issuer} of the key feed. */
            public Builder issuer(String v) { this.issuer = v; return this; }
            /** The expected {@code project_id} claim. Default: the {@code projectId} of the classify result passed in. */
            public Builder projectId(String v) { this.projectId = v; return this; }
            /** Allowed clock difference in seconds (default 60). */
            public Builder clockSkewSeconds(long v) { this.clockSkewSeconds = v; return this; }
            /** The clock used for {@code iat} and {@code exp} (tests). */
            public Builder clock(Clock v) { this.clock = v; return this; }
            public VerifyOptions build() { return new VerifyOptions(this); }
        }
    }

    private static KError invalid(String message, Map<String, Object> context) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, context, null, null, null);
    }

    /**
     * As {@link #classify(Map, String)} for an input you hold as an {@code Object} (for example a parsed JSON value).
     * Only a JSON object is accepted ({@link Map} or an object {@code JsonNode}); a string, number, array, boolean or
     * {@code null} is refused with {@code K9O_INVALID_FIELD_VALUE} before anything is sent.
     */
    @SuppressWarnings("unchecked")
    public Classification classify(Object input, String label) {
        if (input instanceof Map<?, ?> m) return classify((Map<String, ?>) m, label);
        if (input instanceof com.fasterxml.jackson.databind.JsonNode n && n.isObject()) {
            return classify(HttpTransport.JSON.convertValue(n, new TypeReference<Map<String, Object>>() {}), label);
        }
        throw invalid("input must be a JSON object", Map.of("field", "input"));
    }

    /** Classify {@code input} (a JSON object) with no label. */
    public Classification classify(Map<String, ?> input) {
        return classify(input, null);
    }

    /**
     * Classify {@code input} (a JSON object handed to the classifier as is). {@code label} (1 to 128 characters, may be
     * {@code null}) names the context. The result holds the verdict fields and the receipt as received. The SDK checks
     * the arguments before sending.
     */
    public Classification classify(Map<String, ?> input, String label) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "rampart operations require projectSlug bound on the client");
        }
        if (input == null) throw invalid("input must be a JSON object", Map.of("field", "input"));
        if (label != null && (label.isEmpty() || label.length() > 128)) {
            throw invalid("label must be 1 to 128 characters", Map.of("field", "label"));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("input", input);
        if (label != null) body.put("label", label);
        return HttpTransport.request(config, "POST", UrlGrammar.projectPath(config.projectSlug, "rampart/classify"),
            body, null, new TypeReference<Envelope<Classification>>() {}).data();
    }

    /** Fetch the key feed ({@code GET /rampart/jwks}, public). */
    public Jwks jwks() {
        String url = HttpTransport.resolveRegionalOrigin(config).replaceAll("/+$", "") + "/rampart/jwks";
        HttpResponse<String> res;
        try {
            res = config.httpClient.send(HttpRequest.newBuilder().uri(URI.create(url)).timeout(Duration.ofSeconds(30))
                .header("accept", "application/json").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new KError("K9O_UPSTREAM_UNAVAILABLE", "Network error reaching " + url + ": " + e.getMessage(),
                Map.of("url", url), "Check connectivity to the gateway; retry with backoff if transient.", null, null);
        }
        int status = res.statusCode();
        if (status >= 200 && status < 300) {
            try {
                return HttpTransport.JSON.readValue(res.body(), Jwks.class);
            } catch (JsonProcessingException e) {
                throw new KError("K9O_INTERNAL_ERROR", "Malformed key feed from " + url + ": " + e.getMessage(),
                    Map.of("url", url, "status", status), null, status, null);
            }
        }
        try {
            Map<String, Object> wire = HttpTransport.JSON.readValue(res.body(), new TypeReference<Map<String, Object>>() {});
            KError err = KError.fromWire(wire, status);
            if (err != null) throw HttpTransport.withRequestIdHeader(err, HttpTransport.requestIdHeader(res.headers()));
        } catch (JsonProcessingException ignored) {
            // fall through
        }
        throw new KError("K9O_INTERNAL_ERROR", "Upstream returned " + status + " with non-KError body",
            Map.of("url", url, "status", status), null, status, HttpTransport.requestIdHeader(res.headers()));
    }

    /** Verify {@code receipt} against {@code input} with the default options. */
    public VerifiedReceipt verifyReceipt(String receipt, Object input) {
        return verifyReceipt(receipt, input, null);
    }

    /** Verify the receipt of a {@link #classify} result against {@code input}; the result's {@code projectId} is the expected project. */
    public VerifiedReceipt verifyReceipt(Classification result, Object input) {
        return verifyReceipt(result, input, null);
    }

    /** As {@link #verifyReceipt(String, Object, VerifyOptions)}, with the receipt and project id taken from a {@link #classify} result. */
    public VerifiedReceipt verifyReceipt(Classification result, Object input, VerifyOptions options) {
        if (result == null) throw new ReceiptRefusal(Reason.MALFORMED, "no receipt");
        return verify(result.classificationReceipt(), result.projectId(), input, options);
    }

    /**
     * Verify a classification receipt for exactly the {@code input} you sent. Checks, in order: the key chosen by the
     * header {@code kid} (from the key set given, else the cached feed, refetched once on an unknown {@code kid}),
     * {@code alg} is {@code EdDSA}, {@code typ} is {@code k9o.rampart.classify.v1}, the signature, then
     * {@code iss} (equal to the option {@code issuer}, else the feed's {@code issuer}; refused as {@code issuer} when
     * neither exists), {@code project_id} (equal to the option {@code projectId}; a bare receipt string with no option
     * is refused as {@code project_unknown}), {@code iat} and {@code exp} within the clock skew, and last that
     * {@code input_sha256} equals the SHA-256 (lowercase hex) of the canonical form of {@code input} under the
     * platform's JCS code-point profile (see {@link Jcs}); an input that profile cannot express, such as a fractional
     * number, is refused as {@code input_hash}.
     * The result is built from the verified claims only. Any failure throws a {@link ReceiptRefusal}; a failure to
     * fetch the key feed throws {@link KError}.
     */
    public VerifiedReceipt verifyReceipt(String receipt, Object input, VerifyOptions options) {
        return verify(receipt, null, input, options);
    }

    private VerifiedReceipt verify(String receipt, String carriedProjectId, Object input, VerifyOptions options) {
        VerifyOptions o = options == null ? VerifyOptions.builder().build() : options;
        if (receipt == null) throw new ReceiptRefusal(Reason.MALFORMED, "no receipt");
        String[] parts = receipt.split("\\.", -1);
        if (parts.length != 3 || parts[0].isEmpty() || parts[1].isEmpty() || parts[2].isEmpty()) {
            throw new ReceiptRefusal(Reason.MALFORMED, "a receipt has three dot-separated parts");
        }
        JsonNode header = decodeObject(parts[0], "header");
        JsonNode claims = decodeObject(parts[1], "claims");
        byte[] sig;
        try {
            sig = Base64.getUrlDecoder().decode(parts[2]);
        } catch (IllegalArgumentException e) {
            throw new ReceiptRefusal(Reason.MALFORMED, "the signature is not base64url");
        }
        if (header.has("crit") || header.has("jwk") || header.has("jku") || header.has("x5c")) {
            throw new ReceiptRefusal(Reason.MALFORMED, "the header carries a key or a critical parameter");
        }

        // 1. the key, chosen by the header kid and nothing else
        if (!header.path("kid").isTextual()) throw new ReceiptRefusal(Reason.MALFORMED, "the header has no kid");
        String kid = header.get("kid").asText();
        Jwks[] feed = new Jwks[1];
        Jwk jwk = findKey(kid, o, feed);
        if (jwk == null) throw new ReceiptRefusal(Reason.KEY_UNKNOWN, "no key " + kid + " in the key feed");
        // 2. alg
        if (!"EdDSA".equals(header.path("alg").asText(null))) throw new ReceiptRefusal(Reason.ALG, "alg must be EdDSA");
        // 3. typ
        if (!RECEIPT_TYP.equals(header.path("typ").asText(null))) throw new ReceiptRefusal(Reason.TYP, "typ must be " + RECEIPT_TYP);
        // signature, before any claim is trusted
        PublicKey key = publicKey(jwk);
        boolean ok;
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            ok = s.verify(sig);
        } catch (java.security.GeneralSecurityException e) {
            ok = false;
        }
        if (!ok) throw new ReceiptRefusal(Reason.SIGNATURE, "the signature does not verify");

        // 4. iss, project, time
        String iss = claims.path("iss").asText(null);
        String wantIss = o.issuer != null ? o.issuer : feed[0].issuer();
        if (wantIss == null || wantIss.isEmpty()) {
            throw new ReceiptRefusal(Reason.ISSUER, "no issuer is advertised by the key feed and none was given");
        }
        if (iss == null || !iss.equals(wantIss)) throw new ReceiptRefusal(Reason.ISSUER, "iss is not " + wantIss);
        String projectId = claims.path("project_id").asText(null);
        String wantProject = o.projectId != null ? o.projectId : carriedProjectId;
        if (wantProject == null || wantProject.isEmpty()) {
            throw new ReceiptRefusal(Reason.PROJECT_UNKNOWN, "no project id was given to compare the receipt with");
        }
        if (projectId == null || !projectId.equals(wantProject)) {
            throw new ReceiptRefusal(Reason.PROJECT, "project_id is not the expected project");
        }
        if (!claims.path("iat").isIntegralNumber() || !claims.path("exp").isIntegralNumber()) {
            throw new ReceiptRefusal(Reason.MALFORMED, "iat and exp must be integers");
        }
        long iat = claims.get("iat").asLong();
        long exp = claims.get("exp").asLong();
        long now = o.clock.instant().getEpochSecond();
        if (now - o.clockSkewSeconds > exp) throw new ReceiptRefusal(Reason.EXPIRED, "the receipt has expired");
        if (iat - o.clockSkewSeconds > now) throw new ReceiptRefusal(Reason.NOT_YET_VALID, "the receipt is not valid yet");

        // 5. the receipt attests exactly this input
        String sha = claims.path("input_sha256").asText(null);
        if (sha == null) throw new ReceiptRefusal(Reason.MALFORMED, "no input_sha256");
        byte[] canonical;
        try {
            canonical = Jcs.canonicalBytes(input);
        } catch (IllegalArgumentException e) {
            throw new ReceiptRefusal(Reason.INPUT_HASH, "the input cannot be canonicalised: " + e.getMessage());
        }
        String actual = hex(sha256(canonical));
        if (!MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII), sha.getBytes(StandardCharsets.UTF_8))) {
            throw new ReceiptRefusal(Reason.INPUT_HASH, "the receipt does not attest this input");
        }

        // 6. the verdict comes from the verified claims
        String classification = claims.path("classification").asText(null);
        if (classification == null || !claims.path("risk_bp").isIntegralNumber() || !claims.path("confidence_bp").isIntegralNumber()
            || !claims.path("categories").isArray() || !claims.path("calibration_version").isTextual()) {
            throw new ReceiptRefusal(Reason.MALFORMED, "the claims are incomplete");
        }
        List<String> categories = new ArrayList<>();
        for (JsonNode c : claims.get("categories")) {
            if (!c.isTextual()) throw new ReceiptRefusal(Reason.MALFORMED, "categories must be strings");
            categories.add(c.asText());
        }
        int windows = 1;
        if (claims.has("windows")) {
            JsonNode w = claims.get("windows");
            if (!w.isIntegralNumber() || !w.canConvertToInt() || w.asInt() < 1) {
                throw new ReceiptRefusal(Reason.MALFORMED, "windows must be a whole number, 1 or more");
            }
            windows = w.asInt();
        }
        return new VerifiedReceipt(classification, claims.get("risk_bp").asInt(), claims.get("confidence_bp").asInt(),
            List.copyOf(categories), projectId, claims.get("calibration_version").asText(), sha, iss, iat, exp, kid, windows);
    }

    private Jwk findKey(String kid, VerifyOptions o, Jwks[] feedOut) {
        if (o.keys != null) {
            feedOut[0] = o.keys;
            return lookup(index(o.keys), kid);
        }
        synchronized (keyLock) {
            boolean fetchedNow = false;
            if (cachedKeys == null) {
                cachedFeed = jwks();
                cachedKeys = index(cachedFeed);
                fetchedNow = true;
            }
            Jwk k = lookup(cachedKeys, kid);
            if (k == null && !fetchedNow) {
                cachedFeed = jwks(); // one refetch on an unknown kid
                cachedKeys = index(cachedFeed);
                k = lookup(cachedKeys, kid);
            }
            feedOut[0] = cachedFeed;
            return k;
        }
    }

    private static Jwk lookup(Map<String, Jwk> keys, String kid) {
        return keys.get(kid);
    }

    private static Map<String, Jwk> index(Jwks set) {
        Map<String, Jwk> m = new HashMap<>();
        for (Jwk k : set.keys()) {
            if (k != null && k.kid() != null && "OKP".equals(k.kty()) && "Ed25519".equals(k.crv()) && k.x() != null
                && (k.alg() == null || "EdDSA".equals(k.alg()))) {
                m.putIfAbsent(k.kid(), k);
            }
        }
        return m;
    }

    private static final byte[] ED25519_SPKI_PREFIX = {0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00};

    private static PublicKey publicKey(Jwk jwk) {
        try {
            byte[] raw = Base64.getUrlDecoder().decode(jwk.x());
            if (raw.length != 32) throw new ReceiptRefusal(Reason.SIGNATURE, "the key is not a 32-byte Ed25519 key");
            byte[] der = new byte[ED25519_SPKI_PREFIX.length + 32];
            System.arraycopy(ED25519_SPKI_PREFIX, 0, der, 0, ED25519_SPKI_PREFIX.length);
            System.arraycopy(raw, 0, der, ED25519_SPKI_PREFIX.length, 32);
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(der));
        } catch (IllegalArgumentException | java.security.GeneralSecurityException e) {
            throw new ReceiptRefusal(Reason.SIGNATURE, "the key cannot be used");
        }
    }

    private static JsonNode decodeObject(String part, String what) {
        try {
            JsonNode n = HttpTransport.JSON.readTree(Base64.getUrlDecoder().decode(part));
            if (n == null || !n.isObject()) throw new ReceiptRefusal(Reason.MALFORMED, "the " + what + " is not a JSON object");
            return n;
        } catch (IllegalArgumentException | IOException e) {
            throw new ReceiptRefusal(Reason.MALFORMED, "the " + what + " is not base64url JSON");
        }
    }

    private static byte[] sha256(byte[] b) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(b);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder(b.length * 2);
        for (byte x : b) sb.append(Character.forDigit((x >> 4) & 15, 16)).append(Character.forDigit(x & 15, 16));
        return sb.toString();
    }
}
