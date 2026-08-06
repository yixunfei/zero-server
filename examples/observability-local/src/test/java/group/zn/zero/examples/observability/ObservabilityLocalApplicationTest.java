package group.zn.zero.examples.observability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.log.LogErrorCode;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogType;
import group.zn.zero.monitor.MonitorErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 本地可观测性示例正反路径测试。
 *
 * @author zn
 */
class ObservabilityLocalApplicationTest {

    /**
     * OFRT-12：验证五类日志、真实错误码、标签拒绝、Prometheus 输出和完整结束。
     */
    @Test
    void shouldRunPositiveAndNegativePathsAndStopCompletely() {
        ObservabilityLocalApplication.DemoResult result = ObservabilityLocalApplication.runDemo();

        assertEquals(5, result.logs().size());
        assertEquals(
                List.of(LogType.BUSINESS, LogType.ERROR, LogType.AUDIT, LogType.PERFORMANCE, LogType.SECURITY),
                result.logs().stream().map(record -> record.logType()).toList());
        assertEquals(
                List.of(LogResult.SUCCESS, LogResult.FAILURE, LogResult.STARTED, LogResult.SUCCESS, LogResult.REJECTED),
                result.logs().stream().map(record -> record.result()).toList());
        assertNull(result.logs().getFirst().errorCode());
        assertNull(result.logs().get(2).errorCode());
        assertEquals(LogErrorCode.SENSITIVE_FIELD_REJECTED, result.sensitiveRejectionCode());
        assertEquals(MonitorErrorCode.METRIC_LABEL_FORBIDDEN, result.labelRejectionCode());
        assertTrue(result.prometheusText().contains("zero_example_observability_operations_total"));
        assertTrue(result.prometheusText().contains("module=\"example\",operation=\"run\",result=\"success\""));
        assertFalse(result.logs().stream().anyMatch(record -> record.fields().containsKey("token")));
        assertTrue(result.stopped());
        assertEquals(
                "zero-observability-local=ok|logs=5"
                        + "|sensitiveRejected=ZERO-LOG-SENSITIVE-FIELD-REJECTED"
                        + "|labelRejected=ZERO-MONITOR-METRIC-LABEL-FORBIDDEN|stopped=true",
                result.summary());
    }
}
