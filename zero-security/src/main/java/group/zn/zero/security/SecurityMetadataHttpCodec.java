package group.zn.zero.security;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/** Bounded HTTP header codec for an application-signed security metadata snapshot. */
public final class SecurityMetadataHttpCodec {
    /** Header carrying the opaque signed snapshot. */
    public static final String HEADER = "X-Zero-Security-Metadata";
    private static final String SEPARATOR = "\u001e";

    private SecurityMetadataHttpCodec() {
    }

    /** Encodes a signed snapshot into one opaque, header-safe value. */
    public static String encode(final SecurityMetadataSnapshot snapshot) {
        SecurityMetadataSnapshot current = Objects.requireNonNull(snapshot, "snapshot");
        String value = String.join(SEPARATOR, current.subject(), current.transport(), current.peerAddress(),
                current.trustedSourceAddress(), current.traceId(), current.correlationId(),
                current.permissions().stream().sorted().collect(Collectors.joining("\u001f")),
                current.assertionReference(), Long.toString(current.issuedAt().toEpochMilli()),
                Long.toString(current.expiresAt().toEpochMilli()), current.signature());
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    /** Decodes an opaque value and validates structural bounds before verifier use. */
    public static SecurityMetadataSnapshot decode(final String encoded) {
        Objects.requireNonNull(encoded, "encoded");
        if (encoded.length() > 8192) throw new IllegalArgumentException("security metadata header too large");
        String[] fields = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split(SEPARATOR, -1);
        if (fields.length != 11) throw new IllegalArgumentException("invalid security metadata header");
        return new SecurityMetadataSnapshot(fields[0], fields[1], fields[2], fields[3], fields[4], fields[5],
                Set.of(fields[6].split("\u001f", -1)), fields[7], Instant.ofEpochMilli(Long.parseLong(fields[8])),
                Instant.ofEpochMilli(Long.parseLong(fields[9])), fields[10]);
    }
}
