package group.zn.zero.monitor;

import java.util.List;
import java.util.Objects;

/**
 * 单次监控采样与告警评估结果。
 *
 * @param systemMetricReport 系统指标采集报告。
 * @param alertEvents 本次触发的告警事件；不可变、有序、可能为空、线程安全。
 * @author zn
 */
public record MonitorCollectionResult(
        SystemMetricCollectionReport systemMetricReport,
        List<AlertEvent> alertEvents) {

    /**
     * 创建不可变监控采集结果。
     *
     * @throws NullPointerException 当报告、告警列表或其中元素为空时抛出。
     */
    public MonitorCollectionResult {
        systemMetricReport = Objects.requireNonNull(systemMetricReport, "systemMetricReport");
        alertEvents = List.copyOf(Objects.requireNonNull(alertEvents, "alertEvents"));
    }
}
