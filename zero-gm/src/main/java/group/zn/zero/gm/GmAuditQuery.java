package group.zn.zero.gm;

import java.time.Instant;
import java.util.Objects;

/** Bounded, stable audit query filter. */
public record GmAuditQuery(
        Instant from,
        Instant to,
        String commandKey,
        String cursor,
        int limit) {
    public GmAuditQuery {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        if (to.isBefore(from)) {
            throw new IllegalArgumentException("to must not precede from");
        }
        if (commandKey != null && (commandKey.isBlank() || commandKey.length() > 128)) {
            throw new IllegalArgumentException("commandKey is invalid");
        }
        if (cursor != null && cursor.length() > 256) {
            throw new IllegalArgumentException("cursor is too long");
        }
        if (limit <= 0 || limit > 200) {
            throw new IllegalArgumentException("limit must be 1..200");
        }
    }
}
