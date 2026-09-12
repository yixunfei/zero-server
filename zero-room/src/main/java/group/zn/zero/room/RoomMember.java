package group.zn.zero.room;

import java.util.Objects;

/** Immutable member snapshot. */
public record RoomMember(String playerId, PlayerSlot slot, long disconnectedAtMillis) {
    public RoomMember {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(slot, "slot");
    }
    public boolean reconnectable(long now, long windowMillis) {
        return slot == PlayerSlot.DISCONNECTED && now - disconnectedAtMillis <= windowMillis;
    }
}
