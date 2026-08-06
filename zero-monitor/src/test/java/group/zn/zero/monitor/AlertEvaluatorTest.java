package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 告警评估器测试。
 *
 * @author zn
 */
class AlertEvaluatorTest {

    /**
     * 验证告警规则匹配后会发布事件。
     */
    @Test
    void evaluatorShouldPublishAlertEvents() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition(
                "zero_cache_hit_ratio", "cache hit ratio", "ratio", List.of("cache")));
        registry.record(new MetricSample(
                "zero_cache_hit_ratio",
                0.2D,
                Map.of("cache", "profile"),
                Instant.parse("2026-06-10T00:00:00Z")));
        InMemoryAlertSink sink = new InMemoryAlertSink();
        AlertRule rule = new AlertRule(
                "LowCacheHit",
                "zero_cache_hit_ratio",
                AlertCondition.LESS_THAN,
                0.5D,
                AlertSeverity.WARNING,
                Map.of("cache", "profile"),
                Duration.ofSeconds(30),
                "cache hit too low");

        List<AlertEvent> events = new AlertEvaluator(List.of(rule), List.of(sink)).evaluate(registry);

        assertEquals(1, events.size());
        assertEquals(1, sink.events().size());
        assertEquals(AlertSeverity.WARNING, events.getFirst().severity());
    }

    /**
     * 验证 Prometheus 告警规则可以导出。
     */
    @Test
    void alertRulesShouldExportPrometheusYaml() {
        AlertRule rule = new AlertRule(
                "HighCpu",
                SystemMetricCollector.SYSTEM_CPU_LOAD,
                AlertCondition.GREATER_THAN,
                0.9D,
                AlertSeverity.CRITICAL,
                Map.of(),
                Duration.ofMinutes(1),
                "cpu high");

        String yaml = new PrometheusAlertRuleExporter().export("zero-server", List.of(rule));

        assertTrue(yaml.contains("alert: HighCpu"));
        assertTrue(yaml.contains(SystemMetricCollector.SYSTEM_CPU_LOAD + " > 0.9"));
        assertTrue(yaml.contains("severity: critical"));
    }
}
