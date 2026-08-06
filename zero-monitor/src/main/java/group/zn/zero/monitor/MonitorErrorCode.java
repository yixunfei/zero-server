package group.zn.zero.monitor;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 监控模块稳定错误码。
 *
 * @author zn
 */
public enum MonitorErrorCode implements ErrorCode {

    /**
     * 指标定义非法。
     */
    METRIC_DEFINITION_INVALID(
            "ZERO-MONITOR-METRIC-DEFINITION-INVALID",
            "metric definition is invalid"),

    /**
     * 同名指标定义冲突。
     */
    METRIC_DEFINITION_CONFLICT(
            "ZERO-MONITOR-METRIC-DEFINITION-CONFLICT",
            "metric definition conflicts with existing definition"),

    /**
     * 指标样本非法。
     */
    METRIC_SAMPLE_INVALID(
            "ZERO-MONITOR-METRIC-SAMPLE-INVALID",
            "metric sample is invalid"),

    /**
     * 指标尚未注册。
     */
    METRIC_NOT_REGISTERED(
            "ZERO-MONITOR-METRIC-NOT-REGISTERED",
            "metric is not registered"),

    /**
     * 指标标签违反全局或扩展安全策略。
     */
    METRIC_LABEL_FORBIDDEN(
            "ZERO-MONITOR-METRIC-LABEL-FORBIDDEN",
            "metric label is forbidden"),

    /**
     * 样本标签与定义 schema 不一致。
     */
    METRIC_LABEL_SCHEMA_MISMATCH(
            "ZERO-MONITOR-METRIC-LABEL-SCHEMA-MISMATCH",
            "metric labels do not match definition schema"),

    /**
     * 单个系统指标探针失败。
     */
    SYSTEM_PROBE_FAILED(
            "ZERO-MONITOR-SYSTEM-PROBE-FAILED",
            "system metric probe failed"),

    /**
     * 严重监控告警已触发。
     */
    CRITICAL_ALERT_TRIGGERED(
            "ZERO-MONITOR-CRITICAL-ALERT-TRIGGERED",
            "critical monitor alert triggered");

    /**
     * 对外稳定错误码。
     */
    private final String code;

    /**
     * 默认错误说明。
     */
    private final String message;

    MonitorErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回系统错误分类。
     *
     * @return 固定为 {@link ErrorCategory#SYSTEM}；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return ErrorCategory.SYSTEM;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码字符串；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
