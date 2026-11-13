package dev.k9o;

import java.util.Collections;
import java.util.Map;

/**
 * KError — the closed-taxonomy error class thrown by every k9o surface
 * on non-2xx gateway responses or SDK-side validation failures.
 *
 * Idiomatic Java shape: extends RuntimeException so
 * callers can use try/catch without checked-exception ceremony. Code-
 * narrowing pattern: caller catches KError and switches on {@code getCode()}.
 */
public class KError extends RuntimeException {

    private final String code;
    private final Map<String, Object> context;
    private final String recommendation;
    private final Integer status;
    private final String requestId;
    private final Double retryAfterSec;

    public KError(String code, String message) {
        this(code, message, Map.of(), null, null, null);
    }

    public KError(
        String code,
        String message,
        Map<String, Object> context,
        String recommendation,
        Integer status,
        String requestId
    ) {
        this(code, message, context, recommendation, status, requestId, null);
    }

    public KError(
        String code,
        String message,
        Map<String, Object> context,
        String recommendation,
        Integer status,
        String requestId,
        Double retryAfterSec
    ) {
        super(message);
        this.code = code;
        this.context = context == null ? Map.of() : Collections.unmodifiableMap(context);
        this.recommendation = recommendation;
        this.status = status;
        this.requestId = requestId;
        this.retryAfterSec = retryAfterSec;
    }

    /** Closed-taxonomy code (e.g. {@code "K9O_TIER_CAP_HIT"}). */
    public String getCode() {
        return code;
    }

    /** Structured per-code payload (unmodifiable). */
    public Map<String, Object> getContext() {
        return context;
    }

    /** Actionable next step; may be null. */
    public String getRecommendation() {
        return recommendation;
    }

    /** HTTP status the gateway returned; null for SDK-side errors. */
    public Integer getStatus() {
        return status;
    }

    /** Gateway-emitted trace id; may be null. */
    public String getRequestId() {
        return requestId;
    }

    /**
     * The step-up challenge of a 401 {@code K9O_STEP_UP_REQUIRED} (read from {@code context.stepUp}); null for any other
     * error or when the challenge is missing or malformed. See {@link Keys#rotate(String, Keys.RotateOptions)}.
     */
    public Keys.StepUpChallenge getStepUp() {
        return Keys.StepUpChallenge.fromContext(context);
    }

    /** Seconds the gateway asks the caller to wait before retrying; null when it did not say. */
    public Double retryAfterSec() {
        return retryAfterSec;
    }

    /** Convenience constructor — adds a recommendation. */
    public KError withRecommendation(String rec) {
        return new KError(code, getMessage(), context, rec, status, requestId, retryAfterSec);
    }

    /**
     * Builds a KError from a decoded gateway error body, or returns null when the
     * body has no string {@code code} and {@code message}. {@code recommendation} may be
     * a string, an object ({@code action} or {@code note}) or any other JSON value; it never
     * makes the decode fail.
     */
    @SuppressWarnings("unchecked")
    static KError fromWire(Map<String, Object> wire, Integer status) {
        if (wire == null || !(wire.get("code") instanceof String code) || !(wire.get("message") instanceof String message)) {
            return null;
        }
        Map<String, Object> context = wire.get("context") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        String recommendation = null;
        Double retry = null;
        Object rec = wire.get("recommendation");
        if (rec instanceof String r) {
            recommendation = r;
        } else if (rec instanceof Map<?, ?> m) {
            if (m.get("action") instanceof String a) recommendation = a;
            else if (m.get("note") instanceof String n) recommendation = n;
            if (m.get("retryAfterSec") instanceof Number n) {
                double d = n.doubleValue();
                if (Double.isFinite(d) && d >= 0) retry = d;
            }
        }
        String requestId = wire.get("requestId") instanceof String rid ? rid : null;
        return new KError(code, message, context, recommendation, status, requestId, retry);
    }

    @Override
    public String toString() {
        if (status != null) {
            return String.format("[%s] %s (status=%d)", code, getMessage(), status);
        }
        return String.format("[%s] %s", code, getMessage());
    }
}
