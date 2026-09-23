package group.zn.zero.room;

/** Immutable room operation statistics. */
public record RoomStats(long joins, long leaves, long reconnects, long readyChanges, long starts, long settlements) {}
