package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Prometheus 文本导出契约测试。
 *
 * @author zn
 */
class PrometheusExporterTest {

    /**
     * 验证 exporter 按定义 schema 而非样本 Map 顺序输出并正确转义。
     */
    @Test
    void exporterShouldUseDefinitionOrderAndEscapeValues() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition(
                "zero_request_total",
                "request \\ count",
                "count",
                List.of("module", "result")));
        LinkedHashMap<String, String> labels = new LinkedHashMap<>();
        labels.put("result", "ok\\done");
        labels.put("module", "gateway\"edge");
        registry.record(new MetricSample(
                "zero_request_total",
                1D,
                labels,
                Instant.parse("2026-08-04T00:00:00Z")));

        String text = new PrometheusExporter().export(registry);

        assertEquals(
                "# HELP zero_request_total request \\\\ count\n"
                        + "# TYPE zero_request_total counter\n"
                        + "zero_request_total{module=\"gateway\\\"edge\",result=\"ok\\\\done\"} "
                        + "1.0 1785801600000\n",
                text);
    }

    /**
     * 验证 Prometheus 非有限数使用规范文本表示。
     */
    @Test
    void exporterShouldFormatNonFiniteValues() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_value", "value", "ratio", List.of("kind")));
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        registry.record(new MetricSample("zero_value", Double.NaN, Map.of("kind", "nan"), now));
        registry.record(new MetricSample("zero_value", Double.POSITIVE_INFINITY, Map.of("kind", "positive"), now));
        registry.record(new MetricSample("zero_value", Double.NEGATIVE_INFINITY, Map.of("kind", "negative"), now));

        String text = new PrometheusExporter().export(registry);

        assertTrue(text.contains("zero_value{kind=\"nan\"} NaN "));
        assertTrue(text.contains("zero_value{kind=\"positive\"} +Inf "));
        assertTrue(text.contains("zero_value{kind=\"negative\"} -Inf "));
    }

    /**
     * 验证 exporter 不会静默跳过任意注册表提供的未注册样本。
     */
    @Test
    void exporterShouldRejectUnregisteredSamples() {
        MetricRegistry registry = fixedRegistry(
                List.of(),
                List.of(new MetricSample(
                        "missing_metric", 1D, Map.of(), Instant.parse("2026-08-04T00:00:00Z"))));

        assertErrorCode(MonitorErrorCode.METRIC_NOT_REGISTERED, () -> new PrometheusExporter().export(registry));
    }

    /**
     * 验证 exporter 不会静默输出与定义 schema 不一致的样本。
     */
    @Test
    void exporterShouldRejectMismatchedSamples() {
        MetricDefinition definition = new MetricDefinition(
                "zero_request_total", "request", "count", List.of("module"));
        MetricSample sample = new MetricSample(
                "zero_request_total",
                1D,
                Map.of("result", "success"),
                Instant.parse("2026-08-04T00:00:00Z"));

        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_SCHEMA_MISMATCH,
                () -> new PrometheusExporter().export(fixedRegistry(List.of(definition), List.of(sample))));
    }

    private MetricRegistry fixedRegistry(
            final List<MetricDefinition> definitions,
            final List<MetricSample> samples) {
        return new MetricRegistry() {
            @Override
            public void register(final MetricDefinition definition) {
                throw new UnsupportedOperationException("read only");
            }

            @Override
            public void record(final MetricSample sample) {
                throw new UnsupportedOperationException("read only");
            }

            @Override
            public List<MetricDefinition> definitions() {
                return definitions;
            }

            @Override
            public List<MetricSample> samples() {
                return samples;
            }
        };
    }

    private void assertErrorCode(final MonitorErrorCode expected, final Runnable action) {
        ZeroException exception = assertThrows(ZeroException.class, action::run);
        assertEquals(expected, exception.errorCode());
    }
}
