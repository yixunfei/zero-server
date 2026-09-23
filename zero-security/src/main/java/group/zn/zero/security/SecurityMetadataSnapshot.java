package group.zn.zero.security;

import java.time.Instant;
import java.util.Objects;
import java.util.Set;

/**
 * Allow-listed, transport-neutral security metadata for an RPC or HTTP hop.
 * It contains no credentials or arbitrary attributes and is not itself proof of authentication.
 *
 * @param subject authenticated subject identifier
 * @param transport originating transport name
 * @param peerAddress originating peer address
 * @param trustedSourceAddress resolved trusted source address
 * @param traceId trace identifier
 * @param correlationId request correlation identifier
 * @param permissions immutable permission set
 * @param assertionReference opaque reference verified by the receiving application
 * @param issuedAt assertion issue time
 * @param expiresAt assertion expiry time
 * @author zn
 */
public record SecurityMetadataSnapshot(
        String subject,
        String transport,
        String peerAddress,
        String trustedSourceAddress,
        String traceId,
        String correlationId,
        Set<String> permissions,
        String assertionReference,
        Instant issuedAt,
        Instant expiresAt,
        String signature) {

    /** Creates a validated immutable snapshot. */
    public SecurityMetadataSnapshot {
        SecurityValues.require(subject, "subject", 256);
        SecurityValues.require(transport, "transport", 32);
        SecurityValues.require(peerAddress, "peerAddress", 128);
        SecurityValues.require(trustedSourceAddress, "trustedSourceAddress", 128);
        SecurityValues.require(traceId, "traceId", 128);
        SecurityValues.require(correlationId, "correlationId", 128);
        SecurityValues.require(assertionReference, "assertionReference", 256);
        permissions = Set.copyOf(Objects.requireNonNull(permissions, "permissions"));
        issuedAt = Objects.requireNonNull(issuedAt, "issuedAt");
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!expiresAt.isAfter(issuedAt)) {
            throw new IllegalArgumentException("expiresAt must be after issuedAt");
        }
        if (signature == null || signature.length() > 512) {
            throw new IllegalArgumentException("signature is invalid");
        }
    }

    /** Creates an allow-listed snapshot from an authenticated context. */
    public static SecurityMetadataSnapshot from(final SecurityContext context, final String assertionReference) {
        SecurityContext current = Objects.requireNonNull(context, "context");
        return new SecurityMetadataSnapshot(current.subject(), current.transport(), current.peerAddress(),
                current.trustedSourceAddress(), current.traceId(), current.correlationId(), current.permissions(),
                assertionReference, current.authenticatedAt(), current.expiresAt(), "");
    }

    /** Returns the canonical bytes covered by an external signature provider. */
    public byte[] canonicalBytes() {
        return String.join("\u0000", subject, transport, peerAddress, trustedSourceAddress, traceId,
                correlationId, String.join("\u001f", permissions.stream().sorted().toList()), assertionReference,
                Long.toString(issuedAt.toEpochMilli()), Long.toString(expiresAt.toEpochMilli())).getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }


    public boolean expired(final Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }
}
