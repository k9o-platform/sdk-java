package dev.k9o;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Application encryption: {@code client.crypto()}. Your application encrypts and decrypts at the API with
 * the project's platform-managed key; you never name a key. The call routes by custody: {@link #encrypt} checks the project's custody mode on every call ({@code GET crypto/mode}, never cached) and never sends plaintext to the platform when the key is in your own KMS or HSM (BYOK or HYOK); those calls go to the registered {@link ClientCore} ({@link #useClientCore}), and without one they throw {@code K9O_ZK_CLIENT_CORE_UNAVAILABLE}. {@link #decrypt} and {@link #rewrap} try the platform first and reroute client-side when the platform answers 409 {@code K9O_ZK_STORE_FLOW_REQUIRED} with route {@code client}. Three calls, each a batch of 1 to 100 items under one
 * crypt: {@link #encrypt}, {@link #decrypt} and {@link #rewrap} (open and re-seal under the current key version in one
 * call, the plaintext never leaves the platform).
 *
 * <p>Wire (project plane): {@code POST /@<slug>/crypto/encrypt}, {@code /crypto/decrypt}, {@code /crypto/rewrap}.
 * Request {@code {crypt, items: [{plaintext | ciphertext, context?}]}}; plaintext is base64 on the wire (this class
 * encodes and decodes it for you), at most 64 KiB per item and 1 MiB per call. A ciphertext is the base64 of one opaque
 * container: this SDK never parses it, keep it as the string you were given. The answer carries one result per item, in
 * item order; each is a result or a {@code code} (see {@link Result}).
 *
 * <p>A crypt is a registered name, 1 to 64 characters of a-z, 0-9 and -, and not {@code actions} (refused client-side with
 * {@code K9O_INVALID_FIELD_VALUE}). Encrypt can answer 404 {@code K9O_CRYPT_NOT_FOUND} (name not registered) or 409
 * {@code K9O_CRYPT_RETIRED} (name retired); both pass through as {@link KError}. Decrypt and rewrap are not refused for either.
 *
 * <p>Who: For machine calls: encrypt needs only a grant on the crypt; decrypt and rewrap need a service token created with the crypt, a workload policy that names it, a scoped personal access token created with step-up, or a session with a fresh step-up; IAM on the crypt
 * decides who may encrypt, decrypt or rewrap (default floor project editor). Extensions, end users and mobile cannot
 * call it. Nothing here retries by itself, in particular not after a 5xx.
 */
public final class Crypto {

    /** Largest plaintext of one item: 64 KiB. */
    public static final int MAX_ITEM_BYTES = 64 * 1024;
    /** Largest total plaintext of one call: 1 MiB. */
    public static final int MAX_CALL_BYTES = 1024 * 1024;
    /** Largest number of items in one call. */
    public static final int MAX_ITEMS = 100;
    /** Largest {@code context}, in UTF-8 bytes. */
    public static final int MAX_CONTEXT_BYTES = 256;

    /** Code of the 409 the platform answers when the call belongs on the client route. */
    public static final String ZK_STORE_FLOW_REQUIRED = "K9O_ZK_STORE_FLOW_REQUIRED";
    /** Code raised (HTTP 501) when a call needs the client route and no {@link ClientCore} is registered. */
    public static final String ZK_CLIENT_CORE_UNAVAILABLE = "K9O_ZK_CLIENT_CORE_UNAVAILABLE";

    /** Code of the 404 the gateway can answer on encrypt when the crypt name is not registered. Passed through as a {@link KError} unchanged. */
    public static final String CRYPT_NOT_FOUND = "K9O_CRYPT_NOT_FOUND";
    /** Code of the 409 the gateway can answer on encrypt when the crypt name is retired. Decrypt and rewrap are not refused for a retired crypt. Passed through as a {@link KError} unchanged. */
    public static final String CRYPT_RETIRED = "K9O_CRYPT_RETIRED";

    /** Code raised by the SDK (no HTTP status) before the call reaches the client core: {@code expectedKeyRefs} is set and the core does not declare that it enforces it. */
    public static final String KEY_PIN_UNENFORCED = "K9O_CRYPTO_KEY_PIN_UNENFORCED";

    /** Code raised by the SDK (no HTTP status) when the custody mode answer is unknown, missing or malformed; nothing was sent. */
    public static final String CUSTODY_UNKNOWN = "K9O_CRYPTO_CUSTODY_UNKNOWN";

    private static final Pattern CRYPT = Pattern.compile("^(?!actions$)[a-z0-9-]{1,64}$");

    private final HttpTransport.Config config;

    private volatile ClientCore clientCore;

    Crypto(HttpTransport.Config config) { this.config = config; }

    /**
     * The client core: opens and seals with a key held in your own KMS or HSM, so the platform never sees plaintext or
     * key. The language's Kibo binding implements this interface and registers it with {@link #useClientCore}. The
     * contract carries no epoch: the router owns the custody check. Items and results are the same types as the
     * server route.
     */
    public interface ClientCore {
        /**
         * Return {@code true} only when this core refuses a key that is not in {@link CoreOptions#expectedKeyRefs()}, before it
         * uses any token or makes any network call. When you set {@code expectedKeyRefs} on the client and the core does not
         * declare this, the call is refused with {@code K9O_CRYPTO_KEY_PIN_UNENFORCED} before it reaches the core, because a pin
         * the core ignores would look like a pin. Default {@code false}.
         */
        default boolean enforcesExpectedKeyRefs() { return false; }

        List<Result> encrypt(String crypt, List<Plain> items, CoreOptions options);

        List<Result> decrypt(String crypt, List<Sealed> items, CoreOptions options);

        List<Result> rewrap(String crypt, List<Sealed> items, CoreOptions options);
    }

    /**
     * What the router passes to the client core on every call. {@code expectedKeyRefs} is the list you set with
     * {@code expectedKeyRefs} in the client options (unmodifiable; empty when you did not set it). When it is not empty
     * the core must refuse a key whose reference (version ignored, see {@link #keyRefMatches}) is not in it, before it uses
     * any token or makes any network call.
     */
    public record CoreOptions(List<String> expectedKeyRefs) {
        public CoreOptions {
            expectedKeyRefs = expectedKeyRefs == null ? List.of() : List.copyOf(expectedKeyRefs);
        }
    }

    /**
     * Check {@code expectedKeyRefs} from the client options and return an unmodifiable copy (an empty list when {@code null}).
     * It must be a non-empty list of non-blank strings without whitespace, and none may contain a wildcard, since a wildcard
     * pins nothing.
     */
    static List<String> checkExpectedKeyRefs(List<String> refs) {
        if (refs == null) return List.of();
        if (refs.isEmpty()) throw invalid("expectedKeyRefs: pass a non-empty list of key identifiers, or leave it out", "expectedKeyRefs");
        for (String r : refs) {
            if (r == null || r.isBlank() || r.codePoints().anyMatch(Character::isWhitespace)) {
                throw invalid("expectedKeyRefs: each entry must be a key identifier without spaces", "expectedKeyRefs");
            }
            if (r.indexOf('*') >= 0) throw invalid("expectedKeyRefs: an entry cannot contain \"*\"; name the exact key", "expectedKeyRefs");
        }
        return List.copyOf(refs);
    }

    /** The provider whose key reference rules apply (see {@link #keyRefMatches(String, List, KeyRefProvider)}). */
    public enum KeyRefProvider { AWS, GCP, AZURE, VAULT }

    private static final String GCP_VERSION_PART = "/cryptoKeyVersions/";

    private static String keyIdentity(String ref, KeyRefProvider provider) {
        switch (provider) {
            case AWS: return ref;
            case GCP: {
                int i = ref.indexOf(GCP_VERSION_PART);
                return i < 0 ? ref : ref.substring(0, i);
            }
            case AZURE: {
                String[] parts = ref.split("/", -1);
                return String.join("/", java.util.Arrays.copyOf(parts, Math.min(5, parts.length))).toLowerCase(java.util.Locale.ROOT);
            }
            default: {
                int cut = ref.lastIndexOf('/');
                String path = cut < 0 ? "" : ref.substring(0, cut);
                String key = cut < 0 ? ref : ref.substring(cut + 1);
                int colon = key.indexOf(':');
                return path + "/" + (colon < 0 ? key : key.substring(0, colon));
            }
        }
    }

    /**
     * Whether {@code keyRef} is one of {@code expected}, ignoring the key version, by the rule the Kibo client core applies:
     * AWS compares the whole reference; GCP drops {@code /cryptoKeyVersions/<n>} and what follows; Azure compares the first
     * five {@code /}-separated parts ({@code https://<host>/keys/<key>}) in lower case, so a version and the case of the host
     * and key name are ignored; Vault compares {@code <path>/<key>} without a {@code :<version>} suffix. The
     * {@code provider} is required (a Vault reference can look like a URL, so it is never guessed from the reference); a
     * {@code null} provider throws {@link NullPointerException}. For authors of a client core; the SDK does not call it.
     */
    public static boolean keyRefMatches(String keyRef, List<String> expected, KeyRefProvider provider) {
        java.util.Objects.requireNonNull(provider, "provider");
        if (keyRef == null || expected == null) return false;
        String k = keyIdentity(keyRef, provider);
        for (String e : expected) {
            if (e != null && keyIdentity(e, provider).equals(k)) return true;
        }
        return false;
    }

    private CoreOptions coreOptions() { return new CoreOptions(config.expectedKeyRefs); }

    /** Register the client core (null removes it). By default none is registered. */
    public void useClientCore(ClientCore core) { this.clientCore = core; }

    /**
     * The project's custody mode and its opaque epoch, which changes with every custody transition. {@code route} is
     * {@code "client"} or {@code "server"} when the gateway named one, otherwise {@code null} (an older gateway, or a value this SDK
     * does not know): follow it, do not infer the route from {@code custodyMode}.
     */
    public record Mode(String custodyMode, String epoch, String route) {
        /** A mode with no route. */
        public Mode(String custodyMode, String epoch) { this(custodyMode, epoch, null); }
    }

    /** {@code GET crypto/mode}: the current custody mode, epoch and route. Not audited; any caller with a crypto grant may read it. */
    public Envelope<Mode> mode() {
        Answer a = readMode(null);
        return new Envelope<>(a.mode(), a.meta());
    }

    /**
     * {@code GET crypto/mode?crypt=<crypt>}: as {@link #mode()}, but the gateway authorizes the caller on that crypt. A personal
     * access token or workload policy scoped to named crypts is refused without one. The crypt is checked first
     * ({@code K9O_INVALID_FIELD_VALUE}); nothing is sent when it is invalid.
     */
    public Envelope<Mode> mode(String crypt) {
        if (crypt == null || !CRYPT.matcher(crypt).matches()) {
            throw invalid("crypt must be 1 to 64 characters of a-z, 0-9 and -, and not \"actions\"", "crypt");
        }
        Answer a = readMode(crypt);
        return new Envelope<>(a.mode(), a.meta());
    }

    /** The mode answer with the raw route field (absent when the gateway sent none). */
    private record Answer(Mode mode, JsonNode route, Envelope.Meta meta) {}

    /** Reads the mode; a non-null {@code crypt} is sent as {@code ?crypt=} so the gateway authorizes the caller on that crypt. */
    private Answer readMode(String crypt) {
        Envelope<JsonNode> env = HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug(), "crypto/mode"),
            null, crypt == null ? null : Map.of("crypt", crypt), new TypeReference<Envelope<JsonNode>>() {});
        JsonNode d = env.data();
        JsonNode m = d == null ? null : d.path("custodyMode");
        JsonNode e = d == null ? null : d.path("epoch");
        if (m == null || !m.isTextual() || m.asText().isEmpty()) {
            throw new KError(CUSTODY_UNKNOWN, "The custody mode answer has no custodyMode; no plaintext was sent",
                Map.of(), null, null, null);
        }
        String epoch = e == null || e.isMissingNode() || e.isNull() || e.isContainerNode() ? "" : e.asText();
        JsonNode r = d.path("route");
        String publicRoute = r.isTextual() && ("client".equals(r.asText()) || "server".equals(r.asText())) ? r.asText() : null;
        return new Answer(new Mode(m.asText(), epoch, publicRoute), r.isMissingNode() ? null : r, env.meta());
    }

    /**
     * A short-lived RS256 JWT for a crypt operation, with its claims. The token is a credential: {@link #toString} redacts it.
     * {@code provider} and {@code projectId} are set when the gateway sends them and {@code null} otherwise; a client core
     * only compares them with its own configuration.
     */
    public record FederatedToken(String token, String expiresAt, String issuer, String subject, String audience, String kid,
                                 String provider, String projectId) {
        /** Without {@code provider} and {@code projectId}. */
        public FederatedToken(String token, String expiresAt, String issuer, String subject, String audience, String kid) {
            this(token, expiresAt, issuer, subject, audience, kid, null, null);
        }

        @Override
        public String toString() {
            return "FederatedToken[token=<redacted>, expiresAt=" + expiresAt + ", issuer=" + issuer + ", subject=" + subject
                + ", audience=" + audience + ", kid=" + kid + ", provider=" + provider + ", projectId=" + projectId + "]";
        }
    }

    /**
     * {@code POST crypto/federated-token}: a token (valid at most 15 minutes) for {@code op} ({@code encrypt}, {@code decrypt} or
     * {@code rewrap}) on {@code crypt}; its {@code subject} reads {@code <project>:crypt:<crypt>:op:<op>}. Keep the token out of logs. Never retried, a 5xx included.
     */
    public Envelope<FederatedToken> federatedToken(String crypt, String op) {
        if (crypt == null || !CRYPT.matcher(crypt).matches()) {
            throw invalid("crypt must be 1 to 64 characters of a-z, 0-9 and -, and not \"actions\"", "crypt");
        }
        if (!"encrypt".equals(op) && !"decrypt".equals(op) && !"rewrap".equals(op)) {
            throw invalid("op must be encrypt, decrypt or rewrap", "op");
        }
        String slug = slug();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("crypt", crypt);
        body.put("op", op);
        // POST: the transport never retries it, not after a 5xx either.
        Envelope<JsonNode> env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "crypto/federated-token"),
            body, null, new TypeReference<Envelope<JsonNode>>() {});
        JsonNode d = env.data();
        String[] f = new String[6];
        String[] names = {"token", "expiresAt", "issuer", "subject", "audience", "kid"};
        for (int i = 0; i < names.length; i++) {
            JsonNode n = d == null ? null : d.path(names[i]);
            if (n == null || !n.isTextual() || n.asText().isEmpty()) {
                throw new KError("K9O_INTERNAL_ERROR", "Unexpected crypto answer: data." + names[i] + " is missing");
            }
            f[i] = n.asText();
        }
        JsonNode pv = d.path("provider"), pj = d.path("projectId");
        String provider = pv.isTextual() ? pv.asText() : null;
        String projectId = pj.isTextual() ? pj.asText() : null;
        return new Envelope<>(new FederatedToken(f[0], f[1], f[2], f[3], f[4], f[5], provider, projectId), env.meta());
    }

    /** A registered crypt name. {@code status} is {@code active} or {@code retired}; {@code retiredAt} is {@code null} while active. */
    public record Crypt(String name, String status, String createdAt, String retiredAt) {}

    /** Code of the 409 on create when the name is already registered. Passed through as a {@link KError}. */
    public static final String CRYPT_ALREADY_EXISTS = "K9O_CRYPT_ALREADY_EXISTS";
    /** Code of the 409 on create when the project holds 256 crypts (retired ones counted). Passed through as a {@link KError}. */
    public static final String CRYPT_LIMIT_EXCEEDED = "K9O_CRYPT_LIMIT_EXCEEDED";

    private static KError badCrypts(String op, String why) {
        return new KError("K9O_INTERNAL_ERROR", "crypto.crypts." + op + ": the gateway answered " + why);
    }

    private static Crypt cryptOf(JsonNode o, String op) {
        if (o == null || !o.isObject()) throw badCrypts(op, "without a crypt");
        JsonNode name = o.get("name"), status = o.get("status"), created = o.get("createdAt"), retired = o.get("retiredAt");
        if (name == null || !name.isTextual() || name.asText().isEmpty()) throw badCrypts(op, "without a name");
        if (status == null || !status.isTextual() || !(status.asText().equals("active") || status.asText().equals("retired"))) {
            throw badCrypts(op, "with an unknown status");
        }
        if (created == null || !created.isTextual() || created.asText().isEmpty()) throw badCrypts(op, "without createdAt");
        if (retired == null || !(retired.isNull() || retired.isTextual())) {
            throw badCrypts(op, "with a retiredAt that is neither a date nor null");
        }
        return new Crypt(name.asText(), status.asText(), created.asText(), retired.isNull() ? null : retired.asText());
    }

    private static String checkCryptName(String name, String op) {
        if (name == null || !CRYPT.matcher(name).matches()) {
            throw invalid("crypto.crypts." + op + ": name must be 1 to 64 characters of a-z, 0-9 and -, and not \"actions\"", "name");
        }
        return name;
    }

    /**
     * {@code GET crypto/crypts}: the crypts you hold (every one with a {@code *} grant on crypts, none for a member with no
     * grant). 403 {@code K9O_ACCESS_DENIED} for a non-member passes through. An answer without a crypts list, or with a
     * malformed crypt, throws {@code K9O_INTERNAL_ERROR} (fail closed).
     */
    public Envelope<List<Crypt>> listCrypts() {
        Envelope<JsonNode> env = HttpTransport.request(config, "GET", UrlGrammar.projectPath(slug(), "crypto/crypts"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
        JsonNode d = env.data();
        JsonNode list = d == null ? null : d.get("crypts");
        if (list == null || !list.isArray()) throw badCrypts("list", "without crypts");
        List<Crypt> out = new ArrayList<>();
        for (JsonNode c : list) out.add(cryptOf(c, "list"));
        return new Envelope<>(List.copyOf(out), env.meta());
    }

    /**
     * {@code POST crypto/crypts}: register a crypt so {@link #encrypt} accepts it. The name is checked first (1 to 64 characters
     * of a-z, 0-9 and -, not {@code actions}; {@code K9O_INVALID_FIELD_VALUE}, field {@code name}) and only the name is sent.
     * Needs {@code crypto:manage_crypts} (project admin by default). 409 {@code K9O_CRYPT_ALREADY_EXISTS},
     * {@code K9O_CRYPT_LIMIT_EXCEEDED} (256 per project, retired ones counted) and {@code K9O_CRYPT_RETIRED} pass through.
     */
    public Envelope<Crypt> createCrypt(String name) {
        String slug = slug();
        checkCryptName(name, "create");
        Envelope<JsonNode> env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "crypto/crypts"),
            Map.of("name", name), null, new TypeReference<Envelope<JsonNode>>() {});
        return new Envelope<>(cryptOf(env.data(), "create"), env.meta());
    }

    /**
     * {@code POST crypto/crypts/<name>/retire} (no body): {@link #encrypt} then refuses the name. Decrypt and rewrap never read
     * the registry, so data stays readable; this does not isolate, enforce or destroy anything. Needs
     * {@code crypto:manage_crypts}; any personal access token is refused ({@code K9O_RBAC_DENY}). 404
     * {@code K9O_CRYPT_NOT_FOUND} and 409 {@code K9O_CRYPT_RETIRED} pass through.
     */
    public Envelope<Crypt> retireCrypt(String name) {
        String slug = slug();
        checkCryptName(name, "retire");
        Envelope<JsonNode> env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "crypto/crypts/" + name + "/retire"),
            null, null, new TypeReference<Envelope<JsonNode>>() {});
        return new Envelope<>(cryptOf(env.data(), "retire"), env.meta());
    }

    private enum Route { SERVER, CLIENT }

    /**
     * Fail closed: anything but a known mode and a known route raises and nothing is sent. On BYOK and HYOK only an explicit
     * route "server" sends plaintext to the server; a missing route means client.
     */
    private static Route routeOf(String custodyMode, String epoch, JsonNode route) {
        String r = route != null && route.isTextual() ? route.asText() : null;
        if (route != null && !"server".equals(r) && !"client".equals(r)) {
            Map<String, Object> ctx = new LinkedHashMap<>();
            ctx.put("custodyMode", custodyMode);
            if (r != null) ctx.put("route", r);
            throw new KError(CUSTODY_UNKNOWN, "The custody mode answer has an unknown route; no plaintext was sent", ctx, null, null, null);
        }
        switch (custodyMode) {
            case "PLATFORM_MANAGED": case "CONFIDENTIAL_WORKLOAD": case "SKR":
                requireEpoch(custodyMode, epoch, "The custody mode answer has no epoch; no plaintext was sent");
                return Route.SERVER;
            case "BYOK": case "HYOK":
                if (!"server".equals(r)) return Route.CLIENT;
                requireEpoch(custodyMode, epoch, "The custody mode answer names the server route but has no epoch; no plaintext was sent");
                return Route.SERVER;
            default:
                throw new KError(CUSTODY_UNKNOWN, "Unknown custody mode; no plaintext was sent",
                    Map.of("custodyMode", custodyMode), null, null, null);
        }
    }

    private static void requireEpoch(String custodyMode, String epoch, String message) {
        if (epoch == null || epoch.isEmpty()) {
            throw new KError(CUSTODY_UNKNOWN, message, Map.of("custodyMode", custodyMode), null, null, null);
        }
    }

    private ClientCore requireCore(String custodyMode) {
        ClientCore core = clientCore;
        if (core == null) {
            throw new KError(ZK_CLIENT_CORE_UNAVAILABLE,
                "This project's key is held in your own KMS; install and register the Kibo client core for this language, or call kibo.encrypt/decrypt directly.",
                Map.of("custodyMode", custodyMode == null ? "" : custodyMode, "requirement", "kibo-client-core"), null, 501, null);
        }
        if (!config.expectedKeyRefs.isEmpty() && !core.enforcesExpectedKeyRefs()) {
            throw new KError(KEY_PIN_UNENFORCED,
                "expectedKeyRefs is set but the client core does not declare that it enforces it; nothing was sent",
                Map.of("custodyMode", custodyMode == null ? "" : custodyMode, "requirement", "client-core-enforces-expected-key-refs"),
                "Use a client core that enforces the pin (enforcesExpectedKeyRefs returns true), or leave expectedKeyRefs out.", null, null);
        }
        return core;
    }

    private static boolean isClientRoute409(KError e) {
        return e.getStatus() != null && e.getStatus() == 409 && ZK_STORE_FLOW_REQUIRED.equals(e.getCode())
            && "client".equals(e.getContext().get("route"));
    }

    private static Envelope<List<Result>> wrap(List<Result> r) { return new Envelope<>(List.copyOf(r), null); }

    /**
     * An item to encrypt: the {@code plaintext} bytes and an optional {@code context} (a UTF-8 string of at most 256
     * bytes, caller-chosen; the same crypt and context must be given on decrypt).
     */
    public record Plain(byte[] plaintext, String context) {
        public static Plain of(byte[] plaintext) { return new Plain(plaintext, null); }

        /** The UTF-8 bytes of {@code text}. */
        public static Plain of(String text) { return new Plain(text == null ? null : text.getBytes(StandardCharsets.UTF_8), null); }

        public Plain withContext(String context) { return new Plain(plaintext, context); }
    }

    /**
     * An item to decrypt or rewrap: the {@code ciphertext} exactly as encrypt returned it, and the {@code context} it
     * was encrypted with (none if none was given).
     */
    public record Sealed(String ciphertext, String context) {
        public static Sealed of(String ciphertext) { return new Sealed(ciphertext, null); }

        public Sealed withContext(String context) { return new Sealed(ciphertext, context); }
    }

    /**
     * The result of one item. On success exactly one of {@code ciphertext} (encrypt, rewrap) or {@code plaintext}
     * (decrypt) is set. On failure of that item {@code code} (for example {@code K9O_ENVELOPE_INTEGRITY_FAILURE},
     * {@code K9O_SEALED_UNDER_RETIRED_KEY}) and {@code message} are set; the SDK cannot tell you why an item could not
     * be opened (the gateway gives one code for every case, so it is no oracle). {@code raw} holds every field of the
     * item as the gateway sent it, unknown ones included; the item shape follows the gateway.
     */
    public record Result(String ciphertext, byte[] plaintext, String code, String message, Map<String, Object> raw) {
        public boolean ok() { return code == null; }

        /** The plaintext as UTF-8 text; null when there is none. */
        public String text() { return plaintext == null ? null : new String(plaintext, StandardCharsets.UTF_8); }
    }

    private static KError invalid(String message, String field) {
        return new KError("K9O_INVALID_FIELD_VALUE", message, Map.of("field", field), null, null, null);
    }

    private static KError tooLarge(String message, String field) {
        return new KError("K9O_PAYLOAD_TOO_LARGE", message, Map.of("field", field), null, null, null);
    }

    private String slug() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "crypto operations require projectSlug bound on the client");
        }
        return config.projectSlug;
    }

    private static void checkCrypt(String crypt, int count) {
        if (crypt == null || !CRYPT.matcher(crypt).matches()) {
            throw invalid("crypt must be 1 to 64 characters of a-z, 0-9 and -, and not \"actions\"", "crypt");
        }
        if (count < 1 || count > MAX_ITEMS) throw invalid("items must hold 1 to 100 entries", "items");
    }

    private static String checkContext(String context, int i) {
        if (context != null && context.getBytes(StandardCharsets.UTF_8).length > MAX_CONTEXT_BYTES) {
            throw invalid("context is at most 256 bytes", "items[" + i + "].context");
        }
        return context;
    }

    /**
     * Encrypt 1 to 100 items under {@code crypt}; the answer holds one ciphertext (or code) per item, in order. The gateway can
     * answer 404 {@code K9O_CRYPT_NOT_FOUND} (name not registered) or 409 {@code K9O_CRYPT_RETIRED} (name retired); both throw as
     * {@link KError} unchanged. Decrypt and rewrap are not refused for either.
     */
    public Envelope<List<Result>> encrypt(String crypt, List<Plain> items) {
        String slug = slug();
        checkCrypt(crypt, items == null ? 0 : items.size());
        List<Map<String, Object>> wire = new ArrayList<>();
        long total = 0;
        for (int i = 0; i < items.size(); i++) {
            Plain it = items.get(i);
            if (it == null || it.plaintext() == null) throw invalid("plaintext is required", "items[" + i + "].plaintext");
            if (it.plaintext().length > MAX_ITEM_BYTES) throw tooLarge("an item is at most 64 KiB", "items[" + i + "].plaintext");
            total += it.plaintext().length;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("plaintext", Base64.getEncoder().encodeToString(it.plaintext()));
            String c = checkContext(it.context(), i);
            if (c != null) m.put("context", c);
            wire.add(m);
        }
        if (total > MAX_CALL_BYTES) throw tooLarge("a call is at most 1 MiB of plaintext", "items");
        for (int attempt = 0; ; attempt++) {
            Answer answer = readMode(crypt); // a fresh custody check on every call, never cached
            Mode mode = answer.mode();
            if (routeOf(mode.custodyMode(), mode.epoch(), answer.route()) == Route.CLIENT) {
                return wrap(requireCore(mode.custodyMode()).encrypt(crypt, List.copyOf(items), coreOptions()));
            }
            try {
                return send(slug, "encrypt", crypt, wire, mode.epoch());
            } catch (KError e) {
                // custody moved between the check and the send: re-check once, then raise
                boolean moved = e.getStatus() != null && e.getStatus() == 409 && ZK_STORE_FLOW_REQUIRED.equals(e.getCode());
                if (!moved || attempt >= 1) throw e;
            }
        }
    }

    /** Decrypt 1 to 100 items under {@code crypt}; each answer holds the plaintext (or a code), in order. */
    public Envelope<List<Result>> decrypt(String crypt, List<Sealed> items) {
        return sealedCall("decrypt", crypt, items);
    }

    /** Open and re-seal 1 to 100 items under the current key version ({@code K9O_SEALED_UNDER_RETIRED_KEY} is fixed this way). */
    public Envelope<List<Result>> rewrap(String crypt, List<Sealed> items) {
        return sealedCall("rewrap", crypt, items);
    }

    private Envelope<List<Result>> sealedCall(String op, String crypt, List<Sealed> items) {
        String slug = slug();
        checkCrypt(crypt, items == null ? 0 : items.size());
        List<Map<String, Object>> wire = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Sealed it = items.get(i);
            if (it == null || it.ciphertext() == null || it.ciphertext().isEmpty()) {
                throw invalid("ciphertext is required", "items[" + i + "].ciphertext");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("ciphertext", it.ciphertext());
            String c = checkContext(it.context(), i);
            if (c != null) m.put("context", c);
            wire.add(m);
        }
        try {
            return send(slug, op, crypt, wire, null);
        } catch (KError e) {
            if (!isClientRoute409(e)) throw e;
            ClientCore core = requireCore(e.getContext().get("custodyMode") instanceof String m ? m : null);
            List<Sealed> copy = List.copyOf(items);
            return wrap("decrypt".equals(op) ? core.decrypt(crypt, copy, coreOptions()) : core.rewrap(crypt, copy, coreOptions()));
        }
    }

    private Envelope<List<Result>> send(String slug, String op, String crypt, List<Map<String, Object>> items, String epoch) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("crypt", crypt);
        body.put("items", items);
        if (epoch != null) body.put("custodyEpoch", epoch);
        // POST: the transport never retries it, not after a 5xx either.
        Envelope<JsonNode> env = HttpTransport.request(config, "POST", UrlGrammar.projectPath(slug, "crypto/" + op),
            body, null, new TypeReference<Envelope<JsonNode>>() {});
        JsonNode arr = env.data() == null ? null : env.data().path("items");
        if (arr == null || !arr.isArray()) throw new KError("K9O_INTERNAL_ERROR", "Unexpected crypto answer: data.items is not a list");
        List<Result> out = new ArrayList<>();
        for (JsonNode n : arr) {
            if (!n.isObject()) throw new KError("K9O_INTERNAL_ERROR", "Unexpected crypto answer: an item is not an object");
            Map<String, Object> raw = HttpTransport.JSON.convertValue(n, new TypeReference<Map<String, Object>>() {});
            byte[] pt = null;
            if (n.path("plaintext").isTextual()) {
                try {
                    pt = Base64.getMimeDecoder().decode(n.get("plaintext").asText());
                } catch (IllegalArgumentException e) {
                    throw new KError("K9O_INTERNAL_ERROR", "Unexpected crypto answer: plaintext is not base64");
                }
            }
            out.add(new Result(n.path("ciphertext").isTextual() ? n.get("ciphertext").asText() : null, pt,
                n.path("code").isTextual() ? n.get("code").asText() : null,
                n.path("message").isTextual() ? n.get("message").asText() : null, raw));
        }
        return new Envelope<>(List.copyOf(out), env.meta());
    }
}
