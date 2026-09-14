package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Short-lived, operation-bound emergency authorization grant. */
public record GmBreakGlassGrant(
        String grantId,
        String scope,
        String reason,
        String operationFingerprint,
        Instant expiresAt,
        boolean secondReviewerRequired) {
    public GmBreakGlassGrant {
        text(grantId, "grantId");
        text(scope, "scope");
        text(reason, "reason");
        text(operationFingerprint, "operationFingerprint");
        Objects.requireNonNull(expiresAt, "expiresAt");
    }

    public boolean expired(final Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    private static void text(final String value, final String name) {
        if (value == null || value.isBlank() || value.length() > 256
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
