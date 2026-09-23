package group.zn.zero.discovery;

/**
 * 服务发现事件类型。
 *
 * @author zn
 */
public enum ServiceEventType {

    /**
     * 服务注册。
     */
    REGISTERED,

    /**
     * 服务注销。
     */
    UNREGISTERED,

    /**
     * 健康状态变更。
     */
    HEALTH_CHANGED,

    /**
     * 最终实例快照变更。
     */
    SNAPSHOT_CHANGED
}
