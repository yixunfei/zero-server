package group.zn.zero.actor;

/**
 * Actor 消息。
 *
 * @param messageId 消息唯一标识。
 * @param laneKey lane 绑定键。
 * @param traceId 链路追踪标识。
 * @param payload 消息体。
 * @author zn
 */
public record ActorMessage(String messageId, LaneKey laneKey, String traceId, Object payload) {

    /**
     * 创建 Actor 消息。
     *
     * @param laneKey lane 绑定键；不可为空。
     * @param payload 消息体；不可为空。
     */
    public ActorMessage(final LaneKey laneKey, final Object payload) {
        this(ActorMessageIds.next(), laneKey, payload);
    }

    private ActorMessage(final String messageId, final LaneKey laneKey, final Object payload) {
        this(messageId, laneKey, messageId, payload);
    }

    /**
     * 继承显式上下文 traceId，同时生成内部消息 ID；线程安全，不产生安全令牌。
     * @param laneKey 所属 Lane，不可为空。
     * @param traceId 上游链路 ID，不可为空。
     * @param payload 业务消息，不可为空。
     * @throws NullPointerException 参数为空。
     */
    public ActorMessage(final LaneKey laneKey, final String traceId, final Object payload) {
        this(ActorMessageIds.next(), laneKey, traceId, payload);
    }

    /**
     * 创建 Actor 消息。
     *
     * @throws NullPointerException 当消息标识、lane key、traceId 或消息体为空时抛出。
     */
    public ActorMessage {
        java.util.Objects.requireNonNull(messageId, "messageId");
        java.util.Objects.requireNonNull(laneKey, "laneKey");
        java.util.Objects.requireNonNull(traceId, "traceId");
        java.util.Objects.requireNonNull(payload, "payload");
    }
}
