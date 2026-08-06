package group.zn.zero.monitor;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;

/**
 * 告警事件。
 *
 * @param ruleName 规则名称。
 * @param metricName 指标名称。
 * @param severity 告警级别。
 * @param value 当前值。
 * @param threshold 阈值。
 * @param labels 指标标签。
 * @param time 告警时间。
 * @param message 告警说明。
 * @author zn
 */
public record AlertEvent(
        String ruleName,
        String metricName,
        AlertSeverity severity,
        double value,
        double threshold,
        Map<String, String> labels,
        Instant time,
        String message) {

    /**
     * 创建告警事件。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     */
    public AlertEvent {
        Objects.requireNonNull(ruleName, "ruleName");
        Objects.requireNonNull(metricName, "metricName");
        Objects.requireNonNull(severity, "severity");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
        Objects.requireNonNull(time, "time");
        Objects.requireNonNull(message, "message");
    }
}
