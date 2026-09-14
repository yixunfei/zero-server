package group.zn.zero.security;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Immutable authenticated request context shared by transports. */
public record SecurityContext(
        String subject,
        Instant authenticatedAt,
        Instant expiresAt,
        String transport,
        String peerAddress,
        String trustedSourceAddress,
        String traceId,
        String correlationId,
        Set<String> permissions,
        Map<String, String> attributes) {
    public SecurityContext {
        SecurityValues.require(subject, "subject", 256);
        java.util.Objects.requireNonNull(authenticatedAt, "authenticatedAt");
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(authenticatedAt)) throw new IllegalArgumentException("expiresAt must be after authenticatedAt");
        SecurityValues.require(transport, "transport", 32);
        SecurityValues.require(peerAddress, "peerAddress", 128);
        SecurityValues.require(trustedSourceAddress, "trustedSourceAddress", 128);
        SecurityValues.require(traceId, "traceId", 128);
        SecurityValues.require(correlationId, "correlationId", 128);
        permissions = Set.copyOf(java.util.Objects.requireNonNull(permissions, "permissions"));
        attributes = SecurityValues.safeAttributes(attributes);
    }
    public boolean expired(Instant now) { return !expiresAt.isAfter(java.util.Objects.requireNonNull(now, "now")); }
    public boolean allows(String permission) { return permissions.contains(java.util.Objects.requireNonNull(permission, "permission")); }
}
