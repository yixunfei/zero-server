package group.zn.zero.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Prometheus 告警规则导出器。
 *
 * @author zn
 */
public final class PrometheusAlertRuleExporter {

    /**
     * 导出 Prometheus alert rules YAML。
     *
     * @param groupName 规则组名称；不可为空。
     * @param rules 规则列表；不可为空。
     * @return YAML 文本；不可为空。
     */
    public String export(final String groupName, final List<AlertRule> rules) {
        StringBuilder builder = new StringBuilder();
        builder.append("groups:").append(System.lineSeparator());
        builder.append("- name: ").append(Objects.requireNonNull(groupName, "groupName")).append(System.lineSeparator());
        builder.append("  rules:").append(System.lineSeparator());
        for (AlertRule rule : List.copyOf(Objects.requireNonNull(rules, "rules"))) {
            builder.append("  - alert: ").append(rule.name()).append(System.lineSeparator());
            builder.append("    expr: ")
                    .append(rule.metricName())
                    .append(formatLabels(rule.labels()))
                    .append(' ')
                    .append(rule.condition().prometheusOperator())
                    .append(' ')
                    .append(rule.threshold())
                    .append(System.lineSeparator());
            builder.append("    for: ").append(rule.holdFor().toSeconds()).append("s").append(System.lineSeparator());
            builder.append("    labels:").append(System.lineSeparator());
            builder.append("      severity: ").append(rule.severity().name().toLowerCase()).append(System.lineSeparator());
            builder.append("    annotations:").append(System.lineSeparator());
            builder.append("      summary: \"").append(escape(rule.message())).append('"').append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String formatLabels(final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "";
        }
        return labels.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> entry.getKey() + "=\"" + escape(entry.getValue()) + "\"")
                .collect(Collectors.joining(",", "{", "}"));
    }

    private String escape(final String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
