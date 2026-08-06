package group.zn.zero.monitor;

/**
 * 告警条件。
 *
 * @author zn
 */
public enum AlertCondition {

    /**
     * 大于阈值。
     */
    GREATER_THAN,

    /**
     * 大于等于阈值。
     */
    GREATER_THAN_OR_EQUAL,

    /**
     * 小于阈值。
     */
    LESS_THAN,

    /**
     * 小于等于阈值。
     */
    LESS_THAN_OR_EQUAL,

    /**
     * 等于阈值。
     */
    EQUAL;

    /**
     * 判断指标值是否满足条件。
     *
     * @param value 指标值。
     * @param threshold 阈值。
     * @return true 表示满足告警条件；线程安全。
     */
    public boolean matches(final double value, final double threshold) {
        return switch (this) {
            case GREATER_THAN -> value > threshold;
            case GREATER_THAN_OR_EQUAL -> value >= threshold;
            case LESS_THAN -> value < threshold;
            case LESS_THAN_OR_EQUAL -> value <= threshold;
            case EQUAL -> Double.compare(value, threshold) == 0;
        };
    }

    /**
     * 返回 Prometheus 表达式操作符。
     *
     * @return 操作符；不可为空；线程安全。
     */
    public String prometheusOperator() {
        return switch (this) {
            case GREATER_THAN -> ">";
            case GREATER_THAN_OR_EQUAL -> ">=";
            case LESS_THAN -> "<";
            case LESS_THAN_OR_EQUAL -> "<=";
            case EQUAL -> "==";
        };
    }
}
