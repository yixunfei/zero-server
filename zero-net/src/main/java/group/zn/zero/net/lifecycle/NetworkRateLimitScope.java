package group.zn.zero.net.lifecycle;

/**
 * 网络限流范围。
 *
 * @author zn
 */
public enum NetworkRateLimitScope {

    /** 当前事件不属于限流。 */
    NONE,
    /** 新连接接入限流。 */
    CONNECTION,
    /** 单连接协议帧限流。 */
    FRAME
}
