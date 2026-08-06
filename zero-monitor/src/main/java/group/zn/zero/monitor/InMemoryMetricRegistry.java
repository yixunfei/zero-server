package group.zn.zero.monitor;

import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 内存指标注册表。
 *
 * <p>用于本地原型和测试。该实现保存定义和样本快照，不承担生产级聚合、滑动窗口或远程导出职责。
 *
 * @author zn
 */
public final class InMemoryMetricRegistry implements MetricRegistry {

    /**
     * 不增加额外限制的标签策略。
     */
    private static final MetricLabelPolicy NO_ADDITIONAL_RESTRICTIONS = new MetricLabelPolicy() {
    };

    /**
     * 指标定义表。
     */
    private final Map<String, MetricDefinition> definitions = new LinkedHashMap<>();

    /**
     * 指标样本列表。
     */
    private final List<MetricSample> samples = new ArrayList<>();

    /**
     * 业务附加标签策略。
     */
    private final MetricLabelPolicy labelPolicy;

    /**
     * 创建仅启用框架全局安全底线的注册表。
     *
     * <p>构造后不创建线程；所有状态操作由实例锁保护。
     */
    public InMemoryMetricRegistry() {
        this(NO_ADDITIONAL_RESTRICTIONS);
    }

    /**
     * 创建带附加标签限制的注册表。
     *
     * @param labelPolicy 附加标签策略；不可为空；只允许收紧全局底线。
     * @throws NullPointerException 当策略为空时抛出。
     */
    public InMemoryMetricRegistry(final MetricLabelPolicy labelPolicy) {
        this.labelPolicy = Objects.requireNonNull(labelPolicy, "labelPolicy");
    }

    /**
     * 注册指标定义。
     *
     * @param definition 指标定义；不可为空。
     * @throws NullPointerException 当定义为空时抛出。
     * @throws ZeroException 当同名定义冲突或附加标签策略拒绝定义时抛出。
     */
    @Override
    public synchronized void register(final MetricDefinition definition) {
        MetricDefinition current = Objects.requireNonNull(definition, "definition");
        MetricDefinition existing = definitions.get(current.name());
        if (existing != null) {
            if (existing.equals(current)) {
                return;
            }
            throw MetricContract.failure(
                    MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                    "registered definition differs from candidate",
                    null);
        }
        validateDefinitionPolicy(current);
        definitions.put(current.name(), current);
    }

    /**
     * 记录指标样本。
     *
     * @param sample 指标样本；不可为空。
     * @throws NullPointerException 当样本为空时抛出。
     * @throws ZeroException 当指标未注册、schema 不一致或附加标签策略拒绝样本时抛出。
     */
    @Override
    public synchronized void record(final MetricSample sample) {
        MetricSample current = Objects.requireNonNull(sample, "sample");
        MetricDefinition definition = definitions.get(current.name());
        if (definition == null) {
            throw MetricContract.failure(
                    MonitorErrorCode.METRIC_NOT_REGISTERED,
                    "sample has no registered definition",
                    null);
        }
        MetricContract.requireMatchingSchema(definition, current);
        validateSamplePolicy(definition, current);
        samples.add(current);
    }

    /**
     * 返回指标定义快照。
     *
     * @return 不可变、有序、可能为空、线程安全的指标定义快照。
     */
    @Override
    public synchronized List<MetricDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    /**
     * 返回指标样本快照。
     *
     * @return 不可变、有序、可能为空、线程安全的指标样本快照。
     */
    @Override
    public synchronized List<MetricSample> samples() {
        return List.copyOf(samples);
    }

    /**
     * 导出本地文本格式。
     *
     * @return 文本导出内容；不可为空；按样本记录顺序输出。
     */
    public synchronized String exportText() {
        StringBuilder builder = new StringBuilder(samples.size() * 64);
        for (int index = 0; index < samples.size(); index++) {
            if (index > 0) {
                builder.append(System.lineSeparator());
            }
            appendSample(builder, samples.get(index));
        }
        return builder.toString();
    }

    /**
     * 清空样本，不清空指标定义。
     *
     * <p>该方法会修改内部状态，线程安全。
     */
    public synchronized void clearSamples() {
        samples.clear();
    }

    /**
     * 格式化单条指标样本。
     *
     * @param sample 指标样本；不可为空。
     * @return 文本行；不可为空。
     */
    private void appendSample(final StringBuilder builder, final MetricSample sample) {
        MetricDefinition definition = definitions.get(sample.name());
        builder.append(sample.name()).append('{');
        List<String> labelNames = definition.labelNames();
        for (int index = 0; index < labelNames.size(); index++) {
            if (index > 0) {
                builder.append(',');
            }
            String labelName = labelNames.get(index);
            builder.append(labelName).append("=\"");
            appendEscapedLabel(builder, sample.labels().get(labelName));
            builder.append('"');
        }
        builder.append("} ").append(sample.value()).append(' ').append(sample.time());
    }

    private void appendEscapedLabel(final StringBuilder builder, final String value) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '"') {
                builder.append('\\');
            }
            builder.append(current);
        }
    }

    private void validateDefinitionPolicy(final MetricDefinition definition) {
        for (String labelName : definition.labelNames()) {
            boolean allowed;
            try {
                allowed = labelPolicy.allowsDefinitionLabel(definition.name(), labelName);
            } catch (RuntimeException exception) {
                throw policyFailure(exception);
            }
            if (!allowed) {
                throw MetricContract.failure(
                        MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                        "additional policy rejected definition label",
                        null);
            }
        }
    }

    private void validateSamplePolicy(final MetricDefinition definition, final MetricSample sample) {
        for (String labelName : definition.labelNames()) {
            boolean allowed;
            try {
                allowed = labelPolicy.allowsSampleLabel(
                        definition.name(), labelName, sample.labels().get(labelName));
            } catch (RuntimeException exception) {
                throw policyFailure(exception);
            }
            if (!allowed) {
                throw MetricContract.failure(
                        MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                        "additional policy rejected sample label",
                        null);
            }
        }
    }

    private ZeroException policyFailure(final RuntimeException cause) {
        return MetricContract.failure(
                MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                "additional label policy failed",
                cause);
    }
}
