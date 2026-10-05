package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
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

    /** holdFor 满足后只发布一次，条件恢复后再次触发。 */
    @Test
    void holdForAndDeduplicationShouldBeApplied() {
        AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-02T00:00:00Z"));
        Clock clock = new Clock() {
            @Override public java.time.ZoneId getZone() { return java.time.ZoneOffset.UTC; }
            @Override public Clock withZone(final java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now.get(); }
        };
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_test_alert", "test", "count", List.of("scope")));
        registry.record(new MetricSample("zero_test_alert", 2, Map.of("scope", "a"), now.get()));
        AlertRule rule = new AlertRule("TestAlert", "zero_test_alert", AlertCondition.GREATER_THAN,
                1, AlertSeverity.WARNING, Map.of("scope", "a"), Duration.ofSeconds(30), "test");
        InMemoryAlertSink sink = new InMemoryAlertSink();
        AlertEvaluator evaluator = new AlertEvaluator(List.of(rule), List.of(sink), clock);

        assertTrue(evaluator.evaluate(registry).isEmpty());
        now.set(now.get().plusSeconds(31));
        assertEquals(1, evaluator.evaluate(registry).size());
        assertTrue(evaluator.evaluate(registry).isEmpty());
    }

    /** A failed sink must not silently suppress the alert on the next evaluation. */
    @Test
    void failedPublicationShouldRemainEligibleForRetry() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_test_alert", "test", "count", List.of()));
        registry.record(new MetricSample("zero_test_alert", 2, Map.of(), Instant.EPOCH));
        AlertRule rule = new AlertRule("TestAlert", "zero_test_alert", AlertCondition.GREATER_THAN,
                1, AlertSeverity.WARNING, Map.of(), Duration.ZERO, "test");
        AtomicInteger attempts = new AtomicInteger();
        AlertSink sink = event -> {
            if (attempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary sink failure");
            }
        };
        AlertEvaluator evaluator = new AlertEvaluator(List.of(rule), List.of(sink));

        assertThrows(IllegalStateException.class, () -> evaluator.evaluate(registry));
        assertEquals(1, evaluator.evaluate(registry).size());
        assertEquals(2, attempts.get());
    }

    /** A later sink failure retries the whole event, including sinks that already returned. */
    @Test
    void laterSinkFailureShouldRetryWholeEvent() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition("zero_test_alert", "test", "count", List.of()));
        registry.record(new MetricSample("zero_test_alert", 2, Map.of(), Instant.EPOCH));
        AlertRule rule = new AlertRule("TestAlert", "zero_test_alert", AlertCondition.GREATER_THAN,
                1, AlertSeverity.WARNING, Map.of(), Duration.ZERO, "test");
        InMemoryAlertSink first = new InMemoryAlertSink();
        AtomicInteger laterAttempts = new AtomicInteger();
        AlertSink later = event -> {
            if (laterAttempts.incrementAndGet() == 1) {
                throw new IllegalStateException("temporary sink failure");
            }
        };
        AlertEvaluator evaluator = new AlertEvaluator(List.of(rule), List.of(first, later));

        assertThrows(IllegalStateException.class, () -> evaluator.evaluate(registry));
        assertEquals(1, evaluator.evaluate(registry).size());
        assertTrue(evaluator.evaluate(registry).isEmpty());
        assertEquals(2, first.events().size());
        assertEquals(2, laterAttempts.get());
    }
}
