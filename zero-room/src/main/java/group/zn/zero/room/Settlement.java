package group.zn.zero.room;

import java.util.Objects;

/** Immutable idempotent settlement result. */
public record Settlement(String idempotencyKey, String result) {
    public Settlement { Objects.requireNonNull(idempotencyKey, "idempotencyKey"); Objects.requireNonNull(result, "result"); }
}
