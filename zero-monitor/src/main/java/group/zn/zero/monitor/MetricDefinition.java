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
        List<String> labelNames) {

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
    }
}
