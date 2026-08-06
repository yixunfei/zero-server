package group.zn.zero.event;

/**
 * 基础不可变事件实现。
 *
 * @param eventId 事件唯一标识。
 * @param eventType 事件类型。
 * @param traceId 链路追踪标识。
 * @author zn
 */
public record BasicZeroEvent(String eventId, EventType eventType, String traceId) implements ZeroEvent {

    /**
     * 创建基础事件。
     *
     * @throws NullPointerException 当事件标识、事件类型或 traceId 为空时抛出。
     */
    public BasicZeroEvent {
        java.util.Objects.requireNonNull(eventId, "eventId");
        java.util.Objects.requireNonNull(eventType, "eventType");
        java.util.Objects.requireNonNull(traceId, "traceId");
    }
}

