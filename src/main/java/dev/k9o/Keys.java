package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * Key inventory of the project: {@code client.keys()}. The keys that wrap the project's data keys, one entry per key
 * version, as metadata only: no key material is ever returned, and neither is the identifier of your external key.
 *
 * <p>Wire (project plane): {@code list} {@code GET /@<slug>/vault/keys}, {@code get} {@code GET /@<slug>/vault/keys/<id>},
 * {@code rotate} {@code POST /@<slug>/vault/keys/<id>/rotate}. Reading needs the project admin or project auditor role;
 * rotating needs the project admin role and a fresh step-up proof. A person's credential only: end-user, extension,
 * service and workload tokens are refused (403 {@code K9O_TRUST_ZONE_VIOLATION}).
 */
public final class Keys {

    private final HttpTransport.Config config;

    Keys(HttpTransport.Config config) { this.config = config; }

    /** Set when the key is your own (BYOK). {@code externalKeyFingerprint} is the first 16 characters of base64url(SHA-256) of your key's exact identifier. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ByokRef(String provider, String region, String externalKeyFingerprint) {}

    /** Set when the key is held in your HSM cluster (HYOK). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HyokRef(String clusterId, String clusterName, String externalKeyFingerprint) {}

    /**
     * One key version. {@code status} is {@code active} (new data keys are wrapped under it), {@code rotating} or
     * {@code revoked}; {@code custodyMode} is {@code PLATFORM_MANAGED}, {@code SKR}, {@code BYOK} or
     * {@code HYOK}. {@code byokRef} and {@code hyokRef} are {@code null} unless the key is your own. Timestamps are ISO 8601;
     * {@code rotatedAt} is {@code null} if never rotated. Any field can be {@code null} if the gateway does not know it.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Key(String id, Integer version, String status, String custodyMode, String createdAt, String rotatedAt,
                      ByokRef byokRef, HyokRef hyokRef) {}

    /** The keys of the project. The list is not paged: it has no cursor (a {@code nextCursor} the gateway may still send is ignored). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Page(List<Key> items) {
        public Page {
            items = items == null ? List.of() : List.copyOf(items);
        }
    }

    /** The answer of {@link #rotate}: {@code status} is {@code rotated}. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Rotation(String keyId, Integer previousVersion, Integer newVersion, String status) {}

    private String path(String tail) {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "keys operations require projectSlug bound on the client");
        }
        return UrlGrammar.projectPath(config.projectSlug, "vault/keys" + tail);
    }

    private static String keyId(String id) {
        if (id == null || id.isEmpty()) {
            throw new KError("K9O_INVALID_FIELD_VALUE", "keyId is required", Map.of("field", "keyId"), null, null, null);
        }
        return Analytics.enc(id);
    }

    /** The active key only. */
    public Envelope<Page> list() {
        return list(false);
    }

    /** {@code all} false lists the active key; true adds the rotating and revoked versions ({@code ?status=all}). */
    public Envelope<Page> list(boolean all) {
        return HttpTransport.request(config, "GET", path(""), null, all ? Map.of("status", "all") : null,
            new TypeReference<Envelope<Page>>() {});
    }

    /** One key by its id ({@code vkey_...}); 404 {@code K9O_NOT_FOUND} when the project has no such key. */
    public Envelope<Key> get(String keyId) {
        return HttpTransport.request(config, "GET", path("/" + keyId(keyId)), null, null, new TypeReference<Envelope<Key>>() {});
    }

    /**
     * The step-up challenge of a 401 {@code K9O_STEP_UP_REQUIRED}: what the proof must echo ({@code scopeSetRoot},
     * {@code nonce}), the {@code methods} on offer ({@code webauthn}, {@code dpop} or {@code mtls}), how long the
     * challenge lives ({@code maxAgeSec}), and {@code rpId} when the method is WebAuthn. A challenge is single use.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StepUpChallenge(String scopeSetRoot, String nonce, String minTier, List<String> methods,
                                  Integer maxAgeSec, Boolean singleUse, String rpId) {
        public StepUpChallenge {
            methods = methods == null ? List.of() : List.copyOf(methods);
        }

        @SuppressWarnings("unchecked")
        static StepUpChallenge fromContext(Map<String, Object> context) {
            if (context == null || !(context.get("stepUp") instanceof Map<?, ?> m)) return null;
            if (!(m.get("scopeSetRoot") instanceof String root) || !(m.get("nonce") instanceof String nonce)) return null;
            List<String> methods = new java.util.ArrayList<>();
            if (m.get("methods") instanceof List<?> l) for (Object o : l) if (o instanceof String s) methods.add(s);
            return new StepUpChallenge(root, nonce, m.get("minTier") instanceof String t ? t : null, methods,
                m.get("maxAgeSec") instanceof Number n ? n.intValue() : null,
                m.get("singleUse") instanceof Boolean b ? b : null,
                m.get("rpId") instanceof String r ? r : null);
        }
    }

    /** What the step-up hook is given: the {@code challenge} and the HTTP {@code method} and full {@code url} the proof will accompany (a DPoP proof binds to both). */
    public record StepUpRequest(StepUpChallenge challenge, String method, String url) {}

    /** What the step-up hook returns: one of the offered {@code method}s and that method's own {@code proof}. */
    public record StepUpProof(String method, String proof) {
        @Override
        public String toString() { return "StepUpProof[method=" + method + ", proof=<redacted>]"; }
    }

    /**
     * Produces a step-up proof for a challenge. The SDK cannot mint one (no route does): your code signs with the
     * credential the challenge asks for. Called at most once per {@link #rotate(String, RotateOptions)}.
     */
    @FunctionalInterface
    public interface StepUpHook {
        StepUpProof prove(StepUpRequest request) throws Exception;
    }

    /** Options of {@link #rotate(String, RotateOptions)}: the optional step-up hook. */
    public record RotateOptions(StepUpHook stepUp) {
        public static RotateOptions none() { return new RotateOptions(null); }

        public static RotateOptions withStepUp(StepUpHook hook) { return new RotateOptions(hook); }
    }

    /** As {@link #rotate(String, RotateOptions)} with no step-up hook: a challenge throws. */
    public Envelope<Rotation> rotate(String keyId) {
        return rotate(keyId, null);
    }

    /**
     * Rotate the project's active key: a new key version is created, every data key is re-wrapped under it, and the
     * previous version is revoked. If the re-wrap fails, nothing changes. Writes to the project's encrypted data wait
     * while the rotation runs. Needs the project admin role and a fresh step-up proof (tier T3, key possession).
     *
     * <p>Step-up: the first call carries no proof and the gateway answers 401 {@code K9O_STEP_UP_REQUIRED} with a
     * challenge. With {@code options.stepUp()} set, the SDK passes the challenge (and the method and URL being called)
     * to your hook, sends the same request again with the {@code x-k9o-step-up-proof} header it builds from the returned
     * {@code method} and {@code proof}, and does this exactly once: a second 401 is thrown as is. Without a hook the
     * {@link KError} is thrown and the challenge is on {@link KError#getStepUp()}. An error thrown by the hook
     * propagates; a hook answer whose method is not on offer is refused with {@code K9O_INVALID_FIELD_VALUE} before
     * anything is sent. The proof is never logged and is not part of any error message.
     *
     * <p>Other errors: 409 {@code K9O_STATE_CONFLICT} (context reason {@code key_not_active} or
     * {@code rotation_in_flight}); 429 {@code K9O_RATE_LIMITED} (reason {@code rotation_rate_cap}: at most 3 rotations in
     * a rolling 24 hours, or {@code key_service_busy}), whose {@link KError#retryAfterSec()} says in seconds when the
     * next rotation is possible; 401 {@code K9O_STEP_UP_PROOF_MALFORMED} for a proof the gateway cannot read. The
     * conflict and the rate cap are answered before the step-up challenge, so a refused rotation does not use up a
     * proof. A rotation is not retried by the SDK.
     */
    public Envelope<Rotation> rotate(String keyId, RotateOptions options) {
        String p = path("/" + keyId(keyId) + "/rotate");
        var type = new TypeReference<Envelope<Rotation>>() {};
        try {
            return HttpTransport.request(config, "POST", p, null, null, type);
        } catch (KError e) {
            StepUpHook hook = options == null ? null : options.stepUp();
            StepUpChallenge ch = e.getStepUp();
            if (hook == null || ch == null || !"K9O_STEP_UP_REQUIRED".equals(e.getCode())
                || e.getStatus() == null || e.getStatus() != 401) {
                throw e;
            }
            StepUpProof proof;
            try {
                proof = hook.prove(new StepUpRequest(ch, "POST", HttpTransport.buildUrl(HttpTransport.resolveRegionalOrigin(config), p, null)));
            } catch (RuntimeException re) {
                throw re;
            } catch (Exception ex) {
                throw new KError("K9O_STEP_UP_REQUIRED", "the step-up hook failed: " + ex.getClass().getSimpleName(),
                    e.getContext(), null, 401, e.getRequestId());
            }
            if (proof == null || proof.method() == null || proof.method().isEmpty() || proof.proof() == null || proof.proof().isEmpty()) {
                throw new KError("K9O_INVALID_FIELD_VALUE", "the step-up hook must return a method and a proof",
                    Map.of("field", "stepUp"), null, null, null);
            }
            if (!ch.methods().isEmpty() && !ch.methods().contains(proof.method())) {
                throw new KError("K9O_INVALID_FIELD_VALUE", "the step-up method is not one the gateway offered",
                    Map.of("field", "stepUp.method", "offered", ch.methods()), null, null, null);
            }
            Map<String, Object> header = new java.util.LinkedHashMap<>();
            header.put("scopeSetRoot", ch.scopeSetRoot());
            header.put("nonce", ch.nonce());
            header.put("method", proof.method());
            header.put("proof", proof.proof());
            String encoded;
            try {
                encoded = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(
                    HttpTransport.JSON.writeValueAsBytes(header));
            } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
                throw new KError("K9O_INTERNAL_ERROR", "failed to encode the step-up proof");
            }
            return HttpTransport.request(config, "POST", p, null, null, Map.of("x-k9o-step-up-proof", encoded), type);
        }
    }
}
