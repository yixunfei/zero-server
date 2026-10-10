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
        permissions.forEach(permission -> SecurityValues.require(permission, "permission", 256));
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

    /**
     * 返回确定性、长度前缀的签名字节；权限按字典序编码，签名字段不参与。
     * @return 新字节数组；有序、非空，调用方可修改，不修改快照；线程安全。
     */
    public byte[] canonicalBytes() {
        var bytes = new java.io.ByteArrayOutputStream();
        try (var output = new java.io.DataOutputStream(bytes)) {
            output.writeInt(1);
            for (String value : java.util.List.of(subject, transport, peerAddress, trustedSourceAddress,
                    traceId, correlationId, assertionReference)) writeField(output, value);
            output.writeInt(permissions.size());
            for (String permission : permissions.stream().sorted().toList()) writeField(output, permission);
            // HTTP/Kafka 元数据线格式精度为毫秒，签名必须使用相同精度。
            output.writeLong(issuedAt.toEpochMilli());
            output.writeLong(expiresAt.toEpochMilli());
            return bytes.toByteArray();
        } catch (java.io.IOException failure) {
            throw new java.io.UncheckedIOException("in-memory canonical encoding failed", failure);
        }
    }

    private static void writeField(final java.io.DataOutputStream output, final String value)
            throws java.io.IOException {
        byte[] encoded = value.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        output.writeInt(encoded.length);
        output.write(encoded);
    }


    public boolean expired(final Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }
}
