package group.zn.zero.monitor;

import java.time.Instant;
import java.util.Map;

/**
 * 指标样本。
 *
 * @param name 指标名称。
 * @param value 指标值。
 * @param labels 低基数标签。
 * @param time 采样时间。
 * @author zn
 */
public record MetricSample(
        String name,
        double value,
        Map<String, String> labels,
        Instant time) {

    /**
     * 创建并校验指标样本。
     *
     * <p>该构造器会生成不可变标签 Map，不修改调用方数据；标签迭代顺序不属于公共契约，
     * exporter 必须使用指标定义中的有序 schema。
     * 构造后的记录不可变且线程安全；NaN 与正负无穷允许进入 exporter 显式格式化。
     *
     * @throws group.zn.zero.core.error.ZeroException 当名称、标签或采样时间违反运行时契约时抛出。
     */
    public MetricSample {
        name = MetricContract.sampleName(name);
        labels = MetricContract.sampleLabels(labels);
        time = MetricContract.sampleTime(time);
    }
}
