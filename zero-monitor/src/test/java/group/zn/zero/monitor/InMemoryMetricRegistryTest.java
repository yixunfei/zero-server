package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 内存指标注册表测试。
 *
 * @author zn
 */
class InMemoryMetricRegistryTest {

    /**
     * 验证指标可以注册、记录并导出文本。
     */
    @Test
    void registryShouldRecordAndExportMetricSamples() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition(
                "zero_test_total",
                "测试次数",
                "count",
                List.of("module", "result")));

        registry.record(new MetricSample(
                "zero_test_total",
                1D,
                Map.of("module", "zero-test", "result", "success"),
                Instant.parse("2026-05-23T00:00:00Z")));

        assertEquals(1, registry.definitions().size());
        assertEquals(1, registry.samples().size());
        assertEquals(
                "zero_test_total{module=\"zero-test\",result=\"success\"} 1.0 2026-05-23T00:00:00Z",
                registry.exportText());
    }

    /**
     * 验证完全相同的定义幂等，而任一稳定字段冲突都会被拒绝。
     */
    @Test
    void registryShouldKeepEquivalentRegistrationIdempotentAndRejectConflicts() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        MetricDefinition definition = new MetricDefinition(
                "zero_test_total",
                "测试次数",
                "count",
                List.of("module", "result"));

        registry.register(definition);
        registry.register(new MetricDefinition(
                "zero_test_total",
                "测试次数",
                "count",
                List.of("module", "result")));

        assertEquals(List.of(definition), registry.definitions());
        assertErrorCode(
                MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                () -> registry.register(new MetricDefinition(
                        "zero_test_total", "other", "count", List.of("module", "result"))));
        assertErrorCode(
                MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                () -> registry.register(new MetricDefinition(
                        "zero_test_total", "测试次数", "seconds", List.of("module", "result"))));
        assertErrorCode(
                MonitorErrorCode.METRIC_DEFINITION_CONFLICT,
                () -> registry.register(new MetricDefinition(
                        "zero_test_total", "测试次数", "count", List.of("result", "module"))));
    }

    /**
     * 验证未注册指标不能记录样本。
     */
    @Test
    void registryShouldRejectUnregisteredMetricSamples() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();

        ZeroException exception = assertThrows(ZeroException.class, () -> registry.record(new MetricSample(
                "missing_metric",
                1D,
                Map.of(),
                Instant.parse("2026-05-23T00:00:00Z"))));

        assertEquals(MonitorErrorCode.METRIC_NOT_REGISTERED, exception.errorCode());
    }

    /**
     * 验证样本标签必须与定义 schema 完全一致，但 Map 输入顺序不影响匹配。
     */
    @Test
    void registryShouldRequireExactLabelSchema() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry();
        registry.register(new MetricDefinition(
                "zero_test_total", "测试次数", "count", List.of("module", "result")));
        LinkedHashMap<String, String> reversed = new LinkedHashMap<>();
        reversed.put("result", "success");
        reversed.put("module", "zero-test");

        registry.record(new MetricSample(
                "zero_test_total", 1D, reversed, Instant.parse("2026-05-23T00:00:00Z")));

        assertEquals(1, registry.samples().size());
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_SCHEMA_MISMATCH,
                () -> registry.record(new MetricSample(
                        "zero_test_total",
                        1D,
                        Map.of("module", "zero-test"),
                        Instant.parse("2026-05-23T00:00:00Z"))));
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_SCHEMA_MISMATCH,
                () -> registry.record(new MetricSample(
                        "zero_test_total",
                        1D,
                        Map.of("module", "zero-test", "operation", "query"),
                        Instant.parse("2026-05-23T00:00:00Z"))));
    }

    /**
     * 验证自定义标签策略只能增加限制，且策略异常会保留 cause 并绑定监控错误码。
     */
    @Test
    void customPolicyShouldOnlyAddRestrictions() {
        IllegalStateException policyFailure = new IllegalStateException("policy unavailable");
        MetricLabelPolicy policy = new MetricLabelPolicy() {
            @Override
            public boolean allowsDefinitionLabel(final String metricName, final String labelName) {
                return !"tenant".equals(labelName);
            }

            @Override
            public boolean allowsSampleLabel(
                    final String metricName,
                    final String labelName,
                    final String labelValue) {
                throw policyFailure;
            }
        };
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry(policy);

        InMemoryMetricRegistry permissiveRegistry = new InMemoryMetricRegistry(new MetricLabelPolicy() {
        });
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                () -> permissiveRegistry.register(new MetricDefinition(
                        "zero_test_total", "测试次数", "count", List.of("TRACE_ID"))));
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                () -> registry.register(new MetricDefinition(
                        "zero_test_total", "测试次数", "count", List.of("tenant"))));

        MetricDefinition definition = new MetricDefinition(
                "zero_test_total", "测试次数", "count", List.of("module"));
        MetricLabelPolicy samplePolicy = new MetricLabelPolicy() {
            @Override
            public boolean allowsSampleLabel(
                    final String metricName,
                    final String labelName,
                    final String labelValue) {
                throw policyFailure;
            }
        };
        InMemoryMetricRegistry sampleRegistry = new InMemoryMetricRegistry(samplePolicy);
        sampleRegistry.register(definition);

        ZeroException exception = assertThrows(ZeroException.class, () -> sampleRegistry.record(new MetricSample(
                "zero_test_total",
                1D,
                Map.of("module", "zero-test"),
                Instant.parse("2026-05-23T00:00:00Z"))));

        assertEquals(MonitorErrorCode.METRIC_LABEL_FORBIDDEN, exception.errorCode());
        assertSame(policyFailure, exception.getCause());
        assertEquals(0, sampleRegistry.samples().size());
    }

    private void assertErrorCode(final MonitorErrorCode expected, final Runnable action) {
        ZeroException exception = assertThrows(ZeroException.class, action::run);
        assertEquals(expected, exception.errorCode());
    }
}
