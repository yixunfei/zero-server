package group.zn.zero.net.lifecycle;

/**
 * 生产网络连接生命周期状态。
 *
 * <p>状态只允许由单个连接所属的 Netty EventLoop 单调推进；业务线程只能读取连接属性快照，
 * 不得直接修改状态。</p>
 *
 * @author zn
 */
public enum ConnectionLifecycleState {

    /**
     * Channel 已接入，尚未收到握手。
     */
    ACCEPTED,

    /**
     * 正在执行轻量握手校验。
     */
    HANDSHAKING,

    /**
     * 正在异步鉴权或协调重连。
     */
    AUTHENTICATING,

    /**
     * 已建立，可向业务执行器投递协议帧。
     */
    ESTABLISHED,

    /**
     * 正在排空或主动关闭。
     */
    DRAINING,

    /**
     * 已因明确原因拒绝。
     */
    REJECTED,

    /**
     * 生命周期已经结束。
     */
    CLOSED
}
