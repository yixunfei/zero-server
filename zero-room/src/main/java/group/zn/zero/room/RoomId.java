package group.zn.zero.room;

import java.util.Objects;

/** Immutable room identifier. */
public record RoomId(String value) {
    public RoomId {
        Objects.requireNonNull(value, "value");
        if (value.isBlank()) throw new IllegalArgumentException("room id must not be blank");
    }
}
