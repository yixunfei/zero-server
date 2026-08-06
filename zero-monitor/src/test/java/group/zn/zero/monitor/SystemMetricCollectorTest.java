package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 系统指标采集器测试。
 *
 * @author zn
 */
class SystemMetricCollectorTest {

    /**
     * 验证系统指标可以注册、采样并导出 Prometheus 文本。
     */
    @Test
    void collectorShouldRegisterCollectAndExportPrometheus() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        SystemMetricCollector collector = new SystemMetricCollector();
        collector.registerDefaults(registry);

        SystemMetricCollectionReport report = collector.collect(registry);
        String text = new PrometheusExporter().export(registry);

        assertTrue(report.attemptedProbeCount() > 0);
        assertFalse(registry.definitions().isEmpty());
        assertFalse(registry.samples().isEmpty());
        assertTrue(text.contains("# HELP " + SystemMetricCollector.JVM_MEMORY_USED));
        assertTrue(text.contains(SystemMetricCollector.JVM_THREAD_COUNT));
    }

    /**
     * 验证单个系统探针失败进入安全不可变报告，且后续探针继续采样。
     */
    @Test
    void collectorShouldReportProbeFailureAndContinueRemainingProbes() {
        InMemoryMetricRegistry delegate = new InMemoryMetricRegistry();
        SystemMetricCollector collector = new SystemMetricCollector();
        collector.registerDefaults(delegate);
        MetricRegistry failMemoryOnce = new MetricRegistry() {
            private boolean failed;

            @Override
            public void register(final MetricDefinition definition) {
                delegate.register(definition);
            }

            @Override
            public void record(final MetricSample sample) {
                if (!failed && SystemMetricCollector.JVM_MEMORY_USED.equals(sample.name())) {
                    failed = true;
                    throw ZeroException.of(MonitorErrorCode.METRIC_SAMPLE_INVALID);
                }
                delegate.record(sample);
            }

            @Override
            public List<MetricDefinition> definitions() {
                return delegate.definitions();
            }

            @Override
            public List<MetricSample> samples() {
                return delegate.samples();
            }
        };

        SystemMetricCollectionReport report = collector.collect(failMemoryOnce);

        assertFalse(report.successful());
        assertEquals(1, report.failures().size());
        SystemMetricCollectionReport.ProbeFailure failure = report.failures().getFirst();
        assertEquals(SystemMetricCollectionReport.Probe.MEMORY, failure.probe());
        assertEquals(MonitorErrorCode.SYSTEM_PROBE_FAILED, failure.errorCode());
        assertEquals(ZeroException.class.getName(), failure.causeType());
        assertTrue(delegate.samples().stream()
                .anyMatch(sample -> SystemMetricCollector.JVM_THREAD_COUNT.equals(sample.name())));
        assertThrows(UnsupportedOperationException.class, () -> report.failures().add(failure));
    }

    /**
     * 验证运行时汇总结果会复制集合且不暴露可变 Throwable。
     */
    @Test
    void collectionResultsShouldBeImmutableAndKeepSafeFailureSummary() {
        SystemMetricCollectionReport.ProbeFailure failure =
                SystemMetricCollectionReport.ProbeFailure.from(
                        SystemMetricCollectionReport.Probe.NETWORK,
                        new IllegalStateException("raw sensitive message"));
        SystemMetricCollectionReport report = new SystemMetricCollectionReport(1, 0, List.of(failure));
        MonitorCollectionResult result = new MonitorCollectionResult(report, List.of());

        assertSame(report, result.systemMetricReport());
        assertEquals(IllegalStateException.class.getName(), failure.causeType());
        assertFalse(failure.toString().contains("raw sensitive message"));
        assertThrows(UnsupportedOperationException.class, () -> result.alertEvents().add(null));
    }
}
