package group.zn.zero.logic;

import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricSample;
import java.util.List;
import java.util.Objects;

/**
 * 带日志与指标观测信号的逻辑流程结果。
 *
 * @param flowResult 基础逻辑流程结果。
 * @param logs 日志记录快照。
 * @param metrics 指标样本快照。
 * @param metricText 指标文本导出。
 * @author zn
 */
public record ObservedLogicFlowResult(
        LogicFlowResult flowResult,
        List<ZeroLogRecord> logs,
        List<MetricSample> metrics,
        String metricText) {

    /**
     * 创建观测逻辑流程结果。
     *
     * @throws NullPointerException 当基础结果、日志、指标或文本导出为空时抛出。
     */
    public ObservedLogicFlowResult {
        Objects.requireNonNull(flowResult, "flowResult");
        Objects.requireNonNull(logs, "logs");
        Objects.requireNonNull(metrics, "metrics");
        Objects.requireNonNull(metricText, "metricText");
        logs = List.copyOf(logs);
        metrics = List.copyOf(metrics);
    }

    /**
     * 返回日志记录快照。
     *
     * @return 不可变、有序、可能为空、线程安全的日志记录快照。
     */
    @Override
    public List<ZeroLogRecord> logs() {
        return logs;
    }

    /**
     * 返回指标样本快照。
     *
     * @return 不可变、有序、可能为空、线程安全的指标样本快照。
     */
    @Override
    public List<MetricSample> metrics() {
        return metrics;
    }
}
