package group.zn.zero.monitor;

import java.util.List;

/**
 * 指标注册表。
 *
 * @author zn
 */
public interface MetricRegistry {

    /**
     * 注册指标定义。
     *
     * @param definition 指标定义；不可为空。
     * @throws NullPointerException 当定义为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 定义冲突或策略拒绝时抛出，必须绑定 ErrorCode。
     */
    void register(MetricDefinition definition);

    /**
     * 记录指标样本。
     *
     * @param sample 指标样本；不可为空。
     * @throws NullPointerException 当样本为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 指标未注册、schema 不匹配或策略拒绝时抛出，必须绑定 ErrorCode。
     */
    void record(MetricSample sample);

    /**
     * 返回指标定义快照。
     *
     * @return 不可变、有序、可能为空、线程安全的指标定义快照。
     */
    List<MetricDefinition> definitions();

    /**
     * 返回指标样本快照。
     *
     * @return 不可变、有序、可能为空、线程安全的指标样本快照。
     */
    List<MetricSample> samples();
}
