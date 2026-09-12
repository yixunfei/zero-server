package group.zn.zero.gm;

import java.util.Map;
import java.util.Objects;

/** Trusted transport metadata supplied by an HTTP or RPC adapter. */
public record GmTransportMetadata(
        String traceId,
        String correlationId,
        String idempotencyKey,
        String trustedSourceIp,
        Map<String, String> securityAttributes,
        boolean authenticated) {
    public GmTransportMetadata {
        traceId = required(traceId, "traceId");
        correlationId = required(correlationId, "correlationId");
        idempotencyKey = required(idempotencyKey, "idempotencyKey");
        trustedSourceIp = required(trustedSourceIp, "trustedSourceIp");
        securityAttributes = Map.copyOf(Objects.requireNonNull(securityAttributes, "securityAttributes"));
        if (!authenticated) {
            throw new IllegalArgumentException("GM transport metadata must be authenticated");
        }
    }

    private static String required(final String value, final String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || value.length() > 256 || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
        return value;
    }
}
