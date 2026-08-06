package group.zn.zero.net.lifecycle;

/**
 * 生命周期事件结果。
 *
 * @author zn
 */
public enum ConnectionLifecycleResult {

    /** 事件仅用于记录当前事实。 */
    OBSERVED,
    /** 事件处理成功。 */
    SUCCEEDED,
    /** 连接或协议帧被拒绝。 */
    REJECTED,
    /** 事件处理失败。 */
    FAILED
}
