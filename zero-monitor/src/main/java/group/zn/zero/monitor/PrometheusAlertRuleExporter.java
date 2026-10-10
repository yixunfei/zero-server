package group.zn.zero.monitor;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
     * @return YAML 文本；不可为空；无共享状态变更，线程安全。
     * @throws IllegalArgumentException 文本含控制字符、非法 YAML Unicode、空组名或 PromQL 标识符非法时抛出。
     * @throws NullPointerException 必需参数为空时抛出。
     */
    public String export(final String groupName, final List<AlertRule> rules) {
        StringBuilder builder = new StringBuilder();
        builder.append("groups:").append(System.lineSeparator());
        if (Objects.requireNonNull(groupName, "groupName").isBlank()) {
            throw new IllegalArgumentException("group name must not be blank");
        }
        builder.append("- name: ").append(quoted(groupName)).append(System.lineSeparator());
        builder.append("  rules:").append(System.lineSeparator());
        for (AlertRule rule : List.copyOf(Objects.requireNonNull(rules, "rules"))) {
            requireIdentifier(rule.metricName(), true);
            builder.append("  - alert: ").append(quoted(rule.name())).append(System.lineSeparator());
            String expression = rule.metricName() + formatLabels(rule.labels()) + ' '
                    + rule.condition().prometheusOperator() + ' ' + rule.threshold();
            builder.append("    expr: ").append(quoted(expression)).append(System.lineSeparator());
            builder.append("    for: ").append(rule.holdFor().toSeconds()).append("s").append(System.lineSeparator());
            builder.append("    labels:").append(System.lineSeparator());
            builder.append("      severity: ").append(rule.severity().name().toLowerCase(Locale.ROOT)).append(System.lineSeparator());
            builder.append("    annotations:").append(System.lineSeparator());
            builder.append("      summary: ").append(quoted(rule.message())).append(System.lineSeparator());
        }
        return builder.toString();
    }

    private String formatLabels(final Map<String, String> labels) {
        if (labels.isEmpty()) {
            return "";
        }
        return labels.entrySet().stream()
                .sorted(Comparator.comparing(Map.Entry::getKey))
                .map(entry -> {
                    requireIdentifier(entry.getKey(), false);
                    return entry.getKey() + "=" + quoted(entry.getValue());
                })
                .collect(Collectors.joining(",", "{", "}"));
    }

    /** 标识符只能是名称，不能携带额外 PromQL 表达式或匹配器。 */
    private void requireIdentifier(final String value, final boolean metric) {
        if (!value.matches(metric ? "[a-zA-Z_:][a-zA-Z0-9_:]*" : "[a-zA-Z_][a-zA-Z0-9_]*")) {
            throw new IllegalArgumentException("invalid alert metric or label identifier");
        }
    }

    /** 无实际换行的双引号标量；PromQL 标签先转义，再整体按 YAML 标量转义。 */
    private String quoted(final String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            // 按码点检查以保留合法补充字符；孤立代理项不属于 YAML 可打印字符。
            if (Character.isISOControl(codePoint) || codePoint == 0x2028 || codePoint == 0x2029
                    || codePoint == 0xFFFE || codePoint == 0xFFFF
                    || codePoint >= Character.MIN_SURROGATE && codePoint <= Character.MAX_SURROGATE) {
                throw new IllegalArgumentException("alert text contains invalid YAML characters");
            }
            offset += Character.charCount(codePoint);
        }
        return '"' + value.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }
}
