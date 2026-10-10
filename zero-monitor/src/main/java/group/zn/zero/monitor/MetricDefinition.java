package group.zn.zero.monitor;

import java.util.List;

/**
 * 指标定义。
 *
 * @param name 指标名称。
 * @param description 指标说明。
 * @param unit 指标单位。
 * @param labelNames 有序标签 schema。
 * @author zn
 */
public record MetricDefinition(
        String name,
        String description,
        String unit,
        List<String> labelNames,
        MetricType type) {

    /** 创建默认 GAUGE；累计计数必须通过完整构造显式选择 COUNTER。 */
    public MetricDefinition(final String name, final String description, final String unit, final List<String> labelNames) {
        this(name, description, unit, labelNames, MetricType.GAUGE);
    }

    /**
     * 创建并校验指标定义。
     *
     * <p>该构造器会复制一次标签 schema，不修改调用方集合。构造后的记录不可变且线程安全。
     *
     * @throws group.zn.zero.core.error.ZeroException 当名称、文本、schema 或标签安全边界非法时抛出。
     */
    public MetricDefinition {
        name = MetricContract.definitionName(name);
        description = MetricContract.definitionText(description, "description");
        unit = MetricContract.definitionText(unit, "unit");
        labelNames = MetricContract.definitionLabels(labelNames);
        type = java.util.Objects.requireNonNull(type, "type");
    }
}
