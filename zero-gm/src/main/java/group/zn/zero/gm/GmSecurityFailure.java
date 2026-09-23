package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Safe, parse-independent failure audit input. */
public record GmSecurityFailure(
        String code,
        String phase,
        String traceId,
        String correlationId,
        int inputLength,
        String structureFingerprint,
        Instant occurredAt) {
    public GmSecurityFailure {
        text(code, "code", 128);
        text(phase, "phase", 64);
        text(traceId, "traceId", 128);
        text(correlationId, "correlationId", 128);
        if (inputLength < 0 || inputLength > 1024 * 1024) {
            throw new IllegalArgumentException("inputLength is invalid");
        }
        text(structureFingerprint, "structureFingerprint", 128);
        Objects.requireNonNull(occurredAt, "occurredAt");
    }

    private static void text(final String value, final String name, final int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }
}
