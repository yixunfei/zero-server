package group.zn.zero.monitor;

/** 显式指标语义，不从名称推断。 @author zn */
public enum MetricType {
    /** 可升可降的瞬时值。 */
    GAUGE,
    /** 单调累计计数，进程重启可归零。 */
    COUNTER
}
