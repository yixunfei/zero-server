package group.zn.zero.runtime.health;

/**
 * 安全、低基数的健康状态。
 *
 * @author zn
 */
public enum HealthStatus {

    /** 完全可用。 */
    HEALTHY,

    /** 仍可服务，但能力或容量下降。 */
    DEGRADED,

    /** 不可用。 */
    UNHEALTHY,

    /** 尚未检查或无法确定。 */
    UNKNOWN
}
