package group.zn.zero.net.lifecycle;

/**
 * 有界的生产连接拒绝原因。
 *
 * <p>枚举值可以进入低基数指标标签；不得把动态异常消息、玩家标识、连接标识或完整 IP
 * 作为拒绝原因。</p>
 *
 * @author zn
 */
public enum ConnectionRejectionReason {

    /** 当前事件没有拒绝原因。 */
    NONE,
    /** 握手超时。 */
    HANDSHAKE_TIMEOUT,
    /** 握手内容非法。 */
    HANDSHAKE_REJECTED,
    /** 协议版本不兼容。 */
    PROTOCOL_VERSION_UNSUPPORTED,
    /** 鉴权超时。 */
    AUTHENTICATION_TIMEOUT,
    /** 鉴权被拒绝。 */
    AUTHENTICATION_REJECTED,
    /** 心跳超时。 */
    HEARTBEAT_TIMEOUT,
    /** 单连接入站预算耗尽。 */
    INBOUND_OVERFLOW,
    /** 连接接入被限流。 */
    CONNECTION_RATE_LIMITED,
    /** 协议帧被限流。 */
    FRAME_RATE_LIMITED,
    /** 重连协调失败。 */
    RECONNECT_FAILED,
    /** 生命周期状态非法。 */
    INVALID_STATE,
    /** 内部处理失败。 */
    INTERNAL_FAILURE
}
