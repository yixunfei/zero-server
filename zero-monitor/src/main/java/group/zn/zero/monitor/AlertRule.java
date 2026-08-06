package group.zn.zero.monitor;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * 告警规则。
 *
 * @param name 规则名称。
 * @param metricName 指标名称。
 * @param condition 条件。
 * @param threshold 阈值。
 * @param severity 告警级别。
 * @param labels 需要匹配的低基数标签。
 * @param holdFor 持续时间。
 * @param message 告警说明。
 * @author zn
 */
public record AlertRule(
        String name,
        String metricName,
        AlertCondition condition,
        double threshold,
        AlertSeverity severity,
        Map<String, String> labels,
        Duration holdFor,
        String message) {

    /**
     * 创建告警规则。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当名称、指标或持续时间非法时抛出。
     */
    public AlertRule {
        name = requireText(name, "name");
        metricName = requireText(metricName, "metricName");
        condition = Objects.requireNonNull(condition, "condition");
        severity = Objects.requireNonNull(severity, "severity");
        labels = Map.copyOf(Objects.requireNonNull(labels, "labels"));
        holdFor = Objects.requireNonNull(holdFor, "holdFor");
        if (holdFor.isNegative()) {
            throw new IllegalArgumentException("holdFor must not be negative");
        }
        message = requireText(message, "message");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
