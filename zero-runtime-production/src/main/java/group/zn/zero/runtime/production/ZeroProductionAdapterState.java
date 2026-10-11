package group.zn.zero.runtime.production;

/**
 * 生产 Adapter 装配状态。
 *
 * <p>状态只描述装配和健康检查阶段，不携带任何连接串、密码或 token。
 *
 * @author zn
 */
public enum ZeroProductionAdapterState {

    /**
     * Adapter 未启用。
     */
    DISABLED,

    /**
     * Adapter 已启用但尚未创建组件。
     */
    ENABLED,

    /**
     * Adapter 已启用但缺少必填配置。
     */
    MISSING_CONFIG,

    /**
     * Adapter 组件已创建或已进入延迟创建计划。
     */
    CREATED,

    /**
     * Adapter 生命周期已启动。
     */
    STARTED,

    /**
     * Adapter 健康检查通过。
     */
    HEALTHY,

    /** 运行期远程 IO 不可用，等待健康探测。 */
    DEGRADED,

    /** 正在执行运行期恢复探测。 */
    RECOVERING,

    /** Adapter 已停止，不再接受请求或探测。 */
    STOPPED,

    /**
     * Adapter 创建、启动或健康检查失败。
     */
    FAILED
}
