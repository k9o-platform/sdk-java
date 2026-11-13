package dev.k9o;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.Map;

/**
 * Answer of a single-record write sent with {@code requireApproval()}: the queued change (HTTP 202), or, when an
 * {@code auto_approve} policy applied the write at once, the normal result of the write.
 *
 * <pre>{@code
 * var result = k9o.from("orders").insert(Map.of("id", "o-1")).requireApproval().execute(Map.class);
 * if (result instanceof GatedWrite.Queued<Map> q) {
 *     System.out.println(q.envelope().data().id());
 * } else if (result instanceof GatedWrite.Applied<Map> a) {
 *     System.out.println(a.envelope().data());
 * }
 * }</pre>
 *
 * {@link #isQueued()} is the plain test. The two cases are the only ones.
 */
public sealed interface GatedWrite<T> permits GatedWrite.Queued, GatedWrite.Applied {

    /** True when the write was queued for approval instead of applied. */
    boolean isQueued();

    /** The write waits for approval. {@code envelope().meta().approval().status()} repeats the status. */
    record Queued<T>(Envelope<Approvals.QueuedChange> envelope) implements GatedWrite<T> {
        @Override public boolean isQueued() { return true; }
    }

    /** An {@code auto_approve} policy applied the write; the envelope is the normal answer of the write. */
    record Applied<T>(Envelope<T> envelope) implements GatedWrite<T> {
        @Override public boolean isQueued() { return false; }
    }

    /**
     * Classify an answer: queued only when both {@code meta.approval.status} and {@code data.status} are
     * {@code pending_approval}, so a row whose own {@code status} column has that value is not mistaken for one.
     * Used by the write builders.
     */
    static <T> GatedWrite<T> of(Envelope<JsonNode> raw, Class<T> rowType) {
        JsonNode data = raw.data();
        Envelope.MetaApproval approval = raw.meta().approval();
        try {
            if (approval != null && "pending_approval".equals(approval.status())
                && data != null && data.isObject() && "pending_approval".equals(data.path("status").asText(null))) {
                return new Queued<>(new Envelope<>(
                    HttpTransport.JSON.treeToValue(data, Approvals.QueuedChange.class), raw.meta()));
            }
            T row = data == null || data.isNull() ? null : HttpTransport.JSON.treeToValue(data, rowType);
            return new Applied<>(new Envelope<>(row, raw.meta()));
        } catch (JsonProcessingException e) {
            throw new KError("K9O_INTERNAL_ERROR", "Malformed write answer: " + e.getMessage(), Map.of(), null, null, null);
        }
    }
}
