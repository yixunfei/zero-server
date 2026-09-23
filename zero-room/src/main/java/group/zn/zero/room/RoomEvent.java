package group.zn.zero.room;

import java.util.Objects;

/** Immutable lifecycle event emitted in room-lane commit order. */
public record RoomEvent(RoomId roomId, long sequence, Type type, String playerId, long occurredAtMillis) {
    public RoomEvent {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(type, "type");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        if (occurredAtMillis < 0) throw new IllegalArgumentException("occurredAtMillis must not be negative");
    }

    public enum Type {
        CREATED, JOINED, LEFT, READY_CHANGED, STARTED, DISCONNECTED, RECONNECTED, SETTLED, CLOSED
    }
}
