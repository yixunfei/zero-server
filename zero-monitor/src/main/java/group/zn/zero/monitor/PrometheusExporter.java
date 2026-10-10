package group.zn.zero.monitor;

import group.zn.zero.core.error.ZeroException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Prometheus 文本格式导出器。
 *
 * <p>该导出器只把当前注册表快照转换为文本，不启动 HTTP 服务。
 *
 * @author zn
 */
public final class PrometheusExporter {

    /**
     * 导出 Prometheus 文本。
     *
     * @param registry 指标注册表；不可为空。
     * @return Prometheus 文本；不可为空；按定义和序列首次出现顺序输出各序列的最新样本。
     * @throws NullPointerException 当注册表为空时抛出。
     * @throws ZeroException 当快照包含冲突定义、未注册样本或 schema 不一致样本时抛出。
     */
    public String export(final MetricRegistry registry) {
        MetricRegistry current = Objects.requireNonNull(registry, "registry");
        StringBuilder builder = new StringBuilder();
        Map<String, MetricDefinition> defined = new LinkedHashMap<>();
        Map<SeriesKey, MetricSample> latest = new LinkedHashMap<>();
        for (MetricDefinition definition : current.definitions()) {
            if (definition == null) {
                throw MetricContract.failure(
                        MonitorErrorCode.METRIC_DEFINITION_INVALID,
                        "registry returned null definition",
                        null);
            }
            MetricDefinition existing = defined.putIfAbsent(definition.name(), definition);
            if (existing != null) {
                if (!existing.equals(definition)) {
                    throw MetricContract.failure(
                            MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                            "registry snapshot contains conflicting definitions",
                            null);
                }
                continue;
            }
            appendDefinition(builder, definition);
        }
        for (MetricSample sample : current.samples()) {
            if (sample == null) {
                throw MetricContract.failure(
                        MonitorErrorCode.METRIC_SAMPLE_INVALID,
                        "registry returned null sample",
                        null);
            }
            MetricDefinition definition = defined.get(sample.name());
            if (definition == null) {
                throw MetricContract.failure(
                        MonitorErrorCode.METRIC_NOT_REGISTERED,
                        "registry snapshot contains unregistered sample",
                        null);
            }
            MetricContract.requireMatchingSchema(definition, sample);
            epochMillis(sample);
            SeriesKey key = new SeriesKey(sample.name(), sample.labels());
            MetricSample previous = latest.get(key);
            if (previous == null || !sample.time().isBefore(previous.time())) {
                latest.put(key, sample);
            }
        }
        for (MetricSample sample : latest.values()) {
            MetricDefinition definition = defined.get(sample.name());
            appendSample(builder, definition, sample);
        }
        return builder.toString();
    }

    private void appendDefinition(final StringBuilder builder, final MetricDefinition definition) {
        builder.append("# HELP ").append(definition.name()).append(' ');
        appendEscapedHelp(builder, definition.description());
        builder.append('\n')
                .append("# TYPE ")
                .append(definition.name())
                .append(' ')
                .append(typeOf(definition))
                .append('\n');
    }

    private void appendSample(
            final StringBuilder builder,
            final MetricDefinition definition,
            final MetricSample sample) {
        builder.append(sample.name());
        appendLabels(builder, definition.labelNames(), sample.labels());
        builder.append(' ').append(formatValue(sample.value())).append(' ');
        builder.append(epochMillis(sample));
        builder.append('\n');
    }

    private long epochMillis(final MetricSample sample) {
        try {
            return sample.time().toEpochMilli();
        } catch (ArithmeticException exception) {
            throw MetricContract.failure(
                    MonitorErrorCode.METRIC_SAMPLE_INVALID,
                    "sample timestamp is outside epoch millisecond range",
                    exception);
        }
    }

    private String typeOf(final MetricDefinition definition) {
        return switch (definition.type()) {
            case COUNTER -> "counter";
            case GAUGE -> "gauge";
        };
    }

    private void appendLabels(
            final StringBuilder builder,
            final List<String> labelNames,
            final Map<String, String> labels) {
        if (labelNames.isEmpty()) {
            return;
        }
        builder.append('{');
        for (int index = 0; index < labelNames.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            String labelName = labelNames.get(index);
            builder.append(labelName).append("=\"");
            appendEscapedLabel(builder, labels.get(labelName));
            builder.append('"');
        }
        builder.append('}');
    }

    private String formatValue(final double value) {
        if (Double.isNaN(value)) {
            return "NaN";
        }
        if (value == Double.POSITIVE_INFINITY) {
            return "+Inf";
        }
        if (value == Double.NEGATIVE_INFINITY) {
            return "-Inf";
        }
        return Double.toString(value);
    }

    private void appendEscapedHelp(final StringBuilder builder, final String value) {
        MetricTextEscaper.append(builder, value, false);
    }

    private void appendEscapedLabel(final StringBuilder builder, final String value) {
        MetricTextEscaper.append(builder, value, true);
    }

    private record SeriesKey(String name, Map<String, String> labels) {
    }
}
