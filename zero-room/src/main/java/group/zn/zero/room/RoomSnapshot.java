package group.zn.zero.room;

import java.util.List;
import java.util.Objects;

/** Immutable room snapshot. */
public record RoomSnapshot(RoomId roomId, RoomState state, int capacity, List<RoomMember> members,
                           long sequence, String settlementId, String settlementResult) {
    public RoomSnapshot {
        Objects.requireNonNull(roomId, "roomId");
        Objects.requireNonNull(state, "state");
        members = List.copyOf(members);
    }
    public int connectedCount() { return (int) members.stream().filter(m -> m.slot() != PlayerSlot.DISCONNECTED && m.slot() != PlayerSlot.LEFT).count(); }
}
