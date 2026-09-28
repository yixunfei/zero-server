package group.zn.zero.room;

import java.util.List;

/**
 * 有界房间事件历史的原子读取结果；不可变、线程安全，不是持久重放日志。
 *
 * @param events 请求序号之后仍保留的事件，按序号升序，不可变、可为空。
 * @param firstAvailableSequence 当前最早保留的序号；尚未产生事件时为 -1。
 * @param latestSequence 当前历史中最新事件的序号；尚未产生事件时为 -1。
 * @param droppedEvents 已淘汰的累计条数。
 * @param gap 请求范围内有事件已被淘汰，需要从业务快照或持久日志恢复。
 * @author zn
 */
public record RoomEventHistory(List<RoomEvent> events, long firstAvailableSequence,
        long latestSequence, long droppedEvents, boolean gap) {
    /** 防御性复制，调用者不能修改历史视图。 */
    public RoomEventHistory { events = List.copyOf(events); }
}
