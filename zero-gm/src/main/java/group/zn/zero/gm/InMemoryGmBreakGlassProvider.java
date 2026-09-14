package group.zn.zero.gm;

import java.time.Instant;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/** Local/test one-shot break-glass verifier; applications must provide durable approval. */
public final class InMemoryGmBreakGlassProvider implements GmBreakGlassProvider {
    private final Set<String> consumed = new HashSet<>();

    @Override
    public synchronized Verification verify(
            final GmBreakGlassGrant grant,
            final String scope,
            final String operationFingerprint,
            final Instant now) {
        Objects.requireNonNull(grant, "grant");
        Objects.requireNonNull(now, "now");
        if (grant.expired(now)) {
            return new Verification(false, "EXPIRED");
        }
        if (!grant.scope().equals(scope) || !grant.operationFingerprint().equals(operationFingerprint)) {
            return new Verification(false, "SCOPE_OR_FINGERPRINT_MISMATCH");
        }
        if (!consumed.add(grant.grantId())) {
            return new Verification(false, "ALREADY_CONSUMED");
        }
        return new Verification(true, "ACCEPTED");
    }
}
