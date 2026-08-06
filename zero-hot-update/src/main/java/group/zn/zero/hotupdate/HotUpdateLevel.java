package group.zn.zero.hotupdate;

/**
 * 热更等级。
 *
 * @author zn
 */
public enum HotUpdateLevel {

    /**
     * 无感热更。
     */
    SEAMLESS,

    /**
     * 卡顿热更。
     */
    STALL,

    /**
     * 闪断热更。
     */
    INTERRUPT,

    /**
     * 降级熔断热更。
     */
    DEGRADED,

    /**
     * 停机维护。
     */
    MAINTENANCE
}

