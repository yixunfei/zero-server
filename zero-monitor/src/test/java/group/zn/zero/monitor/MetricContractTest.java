package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 指标定义与样本运行时契约测试。
 *
 * @author zn
 */
class MetricContractTest {

    /**
     * 验证定义 schema 防御性复制、保持顺序且不可修改。
     */
    @Test
    void definitionShouldKeepOrderedImmutableSchema() {
        List<String> input = new ArrayList<>(List.of("module", "operation", "result"));

        MetricDefinition definition = new MetricDefinition(
                "zero_request_total", "请求次数", "count", input);
        input.set(0, "changed");

        assertEquals(List.of("module", "operation", "result"), definition.labelNames());
        assertThrows(UnsupportedOperationException.class, () -> definition.labelNames().add("extra"));
    }

    /**
     * 验证非法指标名称、文本、重复 schema 和标签上限均绑定定义错误码。
     */
    @Test
    void definitionShouldRejectInvalidStructure() {
        assertDefinitionInvalid("", "description", "count", List.of());
        assertDefinitionInvalid("9metric", "description", "count", List.of());
        assertDefinitionInvalid("a".repeat(201), "description", "count", List.of());
        assertDefinitionInvalid("metric", "", "count", List.of());
        assertDefinitionInvalid("metric", "description\nunsafe", "count", List.of());
        assertDefinitionInvalid("metric", "description", "", List.of());
        assertDefinitionInvalid("metric", "description", "count", List.of("module", "module"));
        assertDefinitionInvalid(
                "metric",
                "description",
                "count",
                List.of("a", "b", "c", "d", "e", "f", "g", "h", "i"));
        assertDefinitionInvalid("metric", "description", "count", List.of("bad-label"));
        assertDefinitionInvalid("metric", "description", "count", List.of("a".repeat(65)));
    }

    /**
     * 验证 denylist 对大小写和下划线别名均不可绕过。
     */
    @Test
    void definitionShouldRejectGloballyForbiddenLabels() {
        assertForbiddenDefinition("traceId");
        assertForbiddenDefinition("TRACE_ID");
        assertForbiddenDefinition("player_id");
        assertForbiddenDefinition("clientIp");
        assertForbiddenDefinition("REMOTE_ADDRESS");
        assertForbiddenDefinition("token");
    }

    /**
     * 验证样本标签防御性复制且不可修改。
     */
    @Test
    void sampleShouldKeepImmutableLabelsWithoutMutatingInput() {
        HashMap<String, String> labels = new HashMap<>();
        labels.put("result", "success");
        labels.put("module", "zero-test");

        MetricSample sample = new MetricSample(
                "zero_request_total", 1D, labels, Instant.parse("2026-08-04T00:00:00Z"));
        labels.put("operation", "changed");

        assertEquals(Map.of("result", "success", "module", "zero-test"), sample.labels());
        assertEquals(2, sample.labels().size());
        assertThrows(UnsupportedOperationException.class, () -> sample.labels().put("extra", "value"));
    }

    /**
     * 验证非法样本结构绑定样本错误码，而 NaN 与正负无穷保持可表示。
     */
    @Test
    void sampleShouldRejectInvalidStructureAndAllowNonFiniteValues() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        assertSampleInvalid("", Map.of(), now);
        assertSampleInvalid("9metric", Map.of(), now);
        assertSampleInvalid("metric", Map.of("bad-label", "value"), now);
        assertSampleInvalid("metric", Map.of("module", "a".repeat(257)), now);
        assertSampleInvalid("metric", Map.of("module", "line\nbreak"), now);

        HashMap<String, String> nullValue = new HashMap<>();
        nullValue.put("module", null);
        assertSampleInvalid("metric", nullValue, now);
        HashMap<String, String> tooManyLabels = new HashMap<>();
        for (char label = 'a'; label <= 'i'; label++) {
            tooManyLabels.put(String.valueOf(label), "value");
        }
        assertSampleInvalid("metric", tooManyLabels, now);
        assertSampleInvalid("metric", Map.of(), null);

        assertEquals(Double.NaN, new MetricSample("metric", Double.NaN, Map.of(), now).value());
        assertEquals(
                Double.POSITIVE_INFINITY,
                new MetricSample("metric", Double.POSITIVE_INFINITY, Map.of(), now).value());
        assertEquals(
                Double.NEGATIVE_INFINITY,
                new MetricSample("metric", Double.NEGATIVE_INFINITY, Map.of(), now).value());
    }

    /**
     * 验证完整 IPv4、IPv6 与禁止名称作为标签时均被全局安全底线拒绝。
     */
    @Test
    void sampleShouldRejectForbiddenNamesAndIpLiteralValues() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        assertForbiddenSample(Map.of("trace_id", "trace-1"), now);
        List<String> ipLiterals = List.of(
                "0.0.0.0",
                "192.168.10.20",
                "255.255.255.255",
                "127.1",
                "192.168.1",
                "2130706433",
                "4294967295",
                "10.16777215",
                "255.16777215",
                "10.20.65535",
                "255.255.65535",
                "::",
                "2001:db8::1",
                "[2001:db8::1]",
                "fe80::1%eth0",
                "1:2:3:4:5:6:7:8",
                "::ffff:192.0.2.1",
                "::ffff:127.1",
                "::ffff:255.16777215",
                "::ffff:255.255.65535");
        for (String ipLiteral : ipLiterals) {
            assertForbiddenSample(Map.of("module", ipLiteral), now);
        }

        MetricSample hostname = new MetricSample("metric", 1D, Map.of("module", "node.example"), now);
        assertEquals("node.example", hostname.labels().get("module"));
        List<String> invalidIpLiterals = List.of(
                "4294967296",
                "256.1",
                "256.0",
                "1.16777216",
                "1.256.0",
                "1.2.65536",
                "256.0.0.0",
                "1.256.0.0",
                "1.2.256.0",
                "1.2.3.256",
                "1.2.3.4.5",
                "::ffff:256.1",
                "::ffff:1.16777216",
                "::ffff:1.2.65536",
                "::ffff:1.2.3.256");
        for (String invalidIpLiteral : invalidIpLiterals) {
            MetricSample sample = new MetricSample(
                    "metric", 1D, Map.of("module", invalidIpLiteral), now);
            assertEquals(invalidIpLiteral, sample.labels().get("module"));
        }
    }

    private void assertDefinitionInvalid(
            final String name,
            final String description,
            final String unit,
            final List<String> labelNames) {
        assertErrorCode(
                MonitorErrorCode.METRIC_DEFINITION_INVALID,
                () -> new MetricDefinition(name, description, unit, labelNames));
    }

    private void assertForbiddenDefinition(final String labelName) {
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                () -> new MetricDefinition("metric", "description", "count", List.of(labelName)));
    }

    private void assertSampleInvalid(final String name, final Map<String, String> labels, final Instant time) {
        assertErrorCode(
                MonitorErrorCode.METRIC_SAMPLE_INVALID,
                () -> new MetricSample(name, 1D, labels, time));
    }

    private void assertForbiddenSample(final Map<String, String> labels, final Instant time) {
        assertErrorCode(
                MonitorErrorCode.METRIC_LABEL_FORBIDDEN,
                () -> new MetricSample("metric", 1D, labels, time));
    }

    private void assertErrorCode(final MonitorErrorCode expected, final Runnable action) {
        ZeroException exception = assertThrows(ZeroException.class, action::run);
        assertEquals(expected, exception.errorCode());
    }
}
