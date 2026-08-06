package group.zn.zero.core.lifecycle;

/**
 * 生命周期状态。
 *
 * @author zn
 */
public enum LifecycleState {

    /**
     * 尚未启动。
     */
    NEW,

    /**
     * 正在启动。
     */
    STARTING,

    /**
     * 正在运行。
     */
    RUNNING,

    /**
     * 正在停止。
     */
    STOPPING,

    /**
     * 已停止。
     */
    STOPPED,

    /**
     * 启停失败。
     */
    FAILED
}
