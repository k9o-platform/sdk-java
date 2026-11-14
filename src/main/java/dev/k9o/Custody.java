package dev.k9o;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.core.type.TypeReference;

import java.util.List;
import java.util.Map;

/**
 * Key custody of the project: {@code client.custody()}. Read-only; changing custody is a Console action.
 *
 * <p>Wire (project plane): {@code status()} is {@code GET /@<slug>/custody/status}, for the project admin and
 * project auditor roles. Another role answers 403, a caller who is not a project member answers 404, and an attached
 * project whose key manager cannot answer gives 502 {@code K9O_UPSTREAM_UNAVAILABLE}.
 */
public final class Custody {

    private final HttpTransport.Config config;

    Custody(HttpTransport.Config config) {
        this.config = config;
    }

    /** The newest custody job: {@code kind}, {@code status} and {@code completedAt} ({@code null} until it completes). */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LastJob(String kind, String status, String completedAt) {}

    /** Attestation of the HSM cluster; {@code status} and {@code verifiedAt} are {@code null} today. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Attestation(String status, String verifiedAt) {}

    /**
     * The HSM behind a project in {@code hsm} custody. {@code vendor} is {@code thales-luna}, {@code entrust-nshield},
     * {@code aws-cloudhsm} or {@code null}; {@code keys} is {@code null} today.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Hsm(String vendor, String clusterId, String region, Attestation attestation,
                      List<Map<String, Object>> keys) {}

    /**
     * {@code mode} is the project's custody mode (for example {@code PLATFORM_MANAGED}); {@code provider} is
     * {@code platform}, {@code external_kms}, {@code hsm} or {@code null}; {@code lastJob} is {@code null} when
     * there is none; {@code hsm} is {@code null} unless the provider is {@code hsm} (and when the server omits it).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Status(String mode, boolean attached, String provider, LastJob lastJob, Hsm hsm) {}

    /** Read the project's custody status. */
    public Envelope<Status> status() {
        if (config.projectSlug == null || config.projectSlug.isEmpty()) {
            throw new KError("K9O_MISSING_PROJECT_CONTEXT", "custody operations require projectSlug bound on the client");
        }
        return HttpTransport.request(config, "GET", UrlGrammar.projectPath(config.projectSlug, "custody/status"),
            null, null, new TypeReference<Envelope<Status>>() {});
    }
}
