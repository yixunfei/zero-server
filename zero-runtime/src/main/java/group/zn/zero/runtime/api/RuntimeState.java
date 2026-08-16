package group.zn.zero.runtime.api;

/**
 * 装配 runtime 的完整状态。
 *
 * @author zn
 */
public enum RuntimeState {

    /** 已完成规划，尚未创建组件或 build resource。 */
    PLANNED,

    /** 已创建全部组件，尚未启动。 */
    BUILT,

    /** 正在按拓扑启动。 */
    STARTING,

    /** 全部组件和 startup health 已通过。 */
    RUNNING,

    /** 正在逆序停止和关闭。 */
    STOPPING,

    /** 已停止且资源已关闭。 */
    STOPPED,

    /** 某阶段失败，可能仍有待重试清理项。 */
    FAILED,

    /** runtime 已永久关闭。 */
    CLOSED
}
