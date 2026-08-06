package group.zn.zero.actor;

import java.util.UUID;

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
        this(UUID.randomUUID().toString(), laneKey, UUID.randomUUID().toString(), payload);
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
