package group.zn.zero.net.lifecycle;

/**
 * 生产连接生命周期事件类型。
 *
 * @author zn
 */
public enum ConnectionLifecycleEventType {

    /** Channel 接入。 */
    CHANNEL_ACCEPTED,
    /** 收到握手帧。 */
    HANDSHAKE_RECEIVED,
    /** 握手通过。 */
    HANDSHAKE_SUCCEEDED,
    /** 发起异步鉴权。 */
    AUTH_REQUESTED,
    /** 鉴权通过。 */
    AUTH_SUCCEEDED,
    /** 发起玩家重连协调。 */
    RECONNECT_REQUESTED,
    /** 玩家重连协调完成。 */
    RECONNECT_COMPLETED,
    /** 收到心跳帧。 */
    HEARTBEAT_RECEIVED,
    /** 心跳超时。 */
    HEARTBEAT_TIMEOUT,
    /** 入站预算耗尽。 */
    INBOUND_OVERFLOW,
    /** 连接或协议帧被限流。 */
    RATE_LIMIT_EXCEEDED,
    /** 状态发生单调迁移。 */
    STATE_TRANSITION,
    /** 连接被明确拒绝。 */
    CONNECTION_REJECTED,
    /** Channel 发生异常。 */
    CHANNEL_ERROR,
    /** Channel 已关闭。 */
    CHANNEL_CLOSED
}
