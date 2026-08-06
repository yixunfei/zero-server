package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.error.ErrorCategory;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 监控模块稳定错误码测试。
 *
 * @author zn
 */
class MonitorErrorCodeTest {

    /**
     * 验证八码的分类、稳定字符串和默认说明与冻结契约完全一致。
     */
    @Test
    void errorCodesShouldMatchFrozenContract() {
        List<ExpectedCode> expected = List.of(
                new ExpectedCode(
                        MonitorErrorCode.METRIC_DEFINITION_INVALID,
                        "ZERO-MONITOR-METRIC-DEFINITION-INVALID",
                        "metric definition is invalid"),
                new ExpectedCode(
                        MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                        "ZERO-MONITOR-METRIC-DEFINITION-CONFLICT",
                        "metric definition conflicts with existing definition"),
                new ExpectedCode(
                        MonitorErrorCode.METRIC_SAMPLE_INVALID,
                        "ZERO-MONITOR-METRIC-SAMPLE-INVALID",
                        "metric sample is invalid"),
                new ExpectedCode(
                        MonitorErrorCode.METRIC_NOT_REGISTERED,
                        "ZERO-MONITOR-METRIC-NOT-REGISTERED",
                        "metric is not registered"),
                new ExpectedCode(
                        MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                        "ZERO-MONITOR-METRIC-LABEL-FORBIDDEN",
                        "metric label is forbidden"),
                new ExpectedCode(
                        MonitorErrorCode.METRIC_LABEL_SCHEMA_MISMATCH,
                        "ZERO-MONITOR-METRIC-LABEL-SCHEMA-MISMATCH",
                        "metric labels do not match definition schema"),
                new ExpectedCode(
                        MonitorErrorCode.SYSTEM_PROBE_FAILED,
                        "ZERO-MONITOR-SYSTEM-PROBE-FAILED",
                        "system metric probe failed"),
                new ExpectedCode(
                        MonitorErrorCode.CRITICAL_ALERT_TRIGGERED,
                        "ZERO-MONITOR-CRITICAL-ALERT-TRIGGERED",
                        "critical monitor alert triggered"));

        assertEquals(MonitorErrorCode.values().length, expected.size());
        for (ExpectedCode item : expected) {
            assertEquals(ErrorCategory.SYSTEM, item.errorCode().category());
            assertEquals(item.code(), item.errorCode().code());
            assertEquals(item.message(), item.errorCode().message());
        }
    }

    /**
     * 期望的稳定错误码字段。
     *
     * @param errorCode 错误码枚举。
     * @param code 稳定字符串。
     * @param message 默认说明。
     * @author zn
     */
    private record ExpectedCode(MonitorErrorCode errorCode, String code, String message) {
    }
}
