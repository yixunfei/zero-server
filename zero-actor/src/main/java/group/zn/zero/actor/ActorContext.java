package group.zn.zero.actor;

/**
 * Actor 消息处理上下文。
 *
 * @param laneKey 当前 lane 绑定键。
 * @param messageId 当前消息唯一标识。
 * @param traceId 当前链路追踪标识。
 * @author zn
 */
public record ActorContext(LaneKey laneKey, String messageId, String traceId) {

    /**
     * 创建 Actor 消息处理上下文。
     *
     * @throws NullPointerException 当 lane key、消息标识或 traceId 为空时抛出。
     */
    public ActorContext {
        java.util.Objects.requireNonNull(laneKey, "laneKey");
        java.util.Objects.requireNonNull(messageId, "messageId");
        java.util.Objects.requireNonNull(traceId, "traceId");
    }

    /**
     * 根据消息创建上下文。
     *
     * @param message Actor 消息；不可为空。
     * @return Actor 上下文；不可为空；线程安全。
     * @throws NullPointerException 当消息为空时抛出。
     */
    public static ActorContext from(final ActorMessage message) {
        java.util.Objects.requireNonNull(message, "message");
        return new ActorContext(message.laneKey(), message.messageId(), message.traceId());
    }
}
