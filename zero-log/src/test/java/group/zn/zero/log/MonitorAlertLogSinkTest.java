package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import group.zn.zero.monitor.AlertEvent;
import group.zn.zero.monitor.AlertSeverity;
import group.zn.zero.monitor.MonitorErrorCode;
import java.time.Instant;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 监控告警到统一日志安全入口的适配测试。
 *
 * @author zn
 */
class MonitorAlertLogSinkTest {

    /**
     * 验证严重告警写入显式 ERROR 日志并绑定专用 ErrorCode。
     */
    @Test
    void criticalAlertShouldWriteErrorLogWithMonitorErrorCode() {
        InMemoryLogSink terminalSink = new InMemoryLogSink();
        LogAppender appender = new LogPipeline(java.util.List.of(), terminalSink);
        MonitorAlertLogSink alertSink = new MonitorAlertLogSink(
                appender,
                new LogSource("game-service", "local-1", "zero-monitor"),
                "trace-alert");

        alertSink.publish(event(AlertSeverity.CRITICAL));

        ZeroLogRecord record = terminalSink.records().getFirst();
        assertEquals(LogLevel.ERROR, record.level());
        assertEquals(LogType.ERROR, record.logType());
        assertEquals(LogResult.FAILURE, record.result());
        assertEquals(MonitorErrorCode.CRITICAL_ALERT_TRIGGERED, record.errorCode());
        assertEquals("CRITICAL", record.fields().get("alert.severity"));
    }

    /**
     * 验证信息和警告告警表达日志写入成功且不伪造错误码。
     */
    @Test
    void nonCriticalAlertsShouldUseExplicitLevelWithoutErrorCode() {
        InMemoryLogSink terminalSink = new InMemoryLogSink();
        MonitorAlertLogSink alertSink = new MonitorAlertLogSink(
                new LogPipeline(java.util.List.of(), terminalSink),
                new LogSource("game-service", "local-1", "zero-monitor"),
                "trace-alert");

        alertSink.publish(event(AlertSeverity.INFO));
        alertSink.publish(event(AlertSeverity.WARNING));

        assertEquals(LogLevel.INFO, terminalSink.records().get(0).level());
        assertEquals(LogLevel.WARN, terminalSink.records().get(1).level());
        assertEquals(LogType.PERFORMANCE, terminalSink.records().get(1).logType());
        assertEquals(LogResult.SUCCESS, terminalSink.records().get(1).result());
        assertNull(terminalSink.records().get(1).errorCode());
    }

    /**
     * 创建告警事件。
     *
     * @param severity 告警级别；不可为空。
     * @return 告警事件；不可为空；线程安全。
     */
    private AlertEvent event(final AlertSeverity severity) {
        return new AlertEvent(
                "HighCpu",
                "zero_system_cpu_load_ratio",
                severity,
                0.95D,
                0.9D,
                Map.of("module", "zero-monitor"),
                Instant.parse("2026-06-10T00:00:00Z"),
                "cpu high");
    }
}
