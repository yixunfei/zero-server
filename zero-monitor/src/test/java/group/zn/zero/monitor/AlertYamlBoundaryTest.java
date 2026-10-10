package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 告警文本不能向 YAML 或 PromQL 注入额外结构。 @author zn */
class AlertYamlBoundaryTest {
    /** 所有动态文本入口均拒绝控制字符和 Unicode 换行。 */
    @Test void rejectsControlsInEveryInputPosition() {
        var exporter = new PrometheusAlertRuleExporter();
        for (String control : List.of("\n", "\r", "\t", "\u0000", "\u0085", "\u2028", "\u2029")) {
            String text = "before" + control + "after";
            assertThrows(IllegalArgumentException.class, () -> exporter.export(text, List.of(rule("Alert", "cpu", Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group", List.of(rule(text, "cpu", Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group", List.of(rule("Alert", text, Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group", List.of(rule("Alert", "cpu", Map.of(text, "ok"), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group", List.of(rule("Alert", "cpu", Map.of("zone", text), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group", List.of(rule("Alert", "cpu", Map.of(), text))));
        }
    }

    /** YAML 禁止的 Unicode 字符和孤立代理项不能原样进入任何动态标量。 */
    @Test void rejectsInvalidYamlUnicodeInEveryInputPosition() {
        var exporter = new PrometheusAlertRuleExporter();
        for (String invalid : List.of("\ufffe", "\uffff", "\ud800", "\udc00")) {
            String text = "before" + invalid + "after";
            assertThrows(IllegalArgumentException.class, () -> exporter.export(text,
                    List.of(rule("Alert", "cpu", Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                    List.of(rule(text, "cpu", Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                    List.of(rule("Alert", text, Map.of(), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                    List.of(rule("Alert", "cpu", Map.of(text, "ok"), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                    List.of(rule("Alert", "cpu", Map.of("zone", text), "ok"))));
            assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                    List.of(rule("Alert", "cpu", Map.of(), text))));
        }
    }

    private static AlertRule rule(String name, String metric, Map<String, String> labels, String message) {
        return new AlertRule(name, metric, AlertCondition.GREATER_THAN, 1,
                AlertSeverity.WARNING, labels, Duration.ofSeconds(5), message);
    }

    /** YAML 和 PromQL 各自转义一次；特殊标量、冒号和注释符不得改变结构。 */
    @Test void quotesScalarsAndSeparatelyEscapesPromqlLabels() {
        String yaml = new PrometheusAlertRuleExporter().export("true: #组\ud83d\ude80", List.of(
                rule("null", "cpu", Map.of("zone", "a\"b\\c: #d"), "消息 \"ok\" \\ #")));
        String expected = "groups:\n"
                + "- name: \"true: #组\ud83d\ude80\"\n"
                + "  rules:\n"
                + "  - alert: \"null\"\n"
                + "    expr: \"cpu{zone=\\\"a\\\\\\\"b\\\\\\\\c: #d\\\"} > 1.0\"\n"
                + "    for: 5s\n"
                + "    labels:\n"
                + "      severity: warning\n"
                + "    annotations:\n"
                + "      summary: \"消息 \\\"ok\\\" \\\\ #\"\n";
        assertEquals(expected, yaml.replace(System.lineSeparator(), "\n"));
    }

    /** 无换行的标识符也不能包含表达式或额外匹配器。 */
    @Test void rejectsExpressionInjectionInIdentifiers() {
        var exporter = new PrometheusAlertRuleExporter();
        assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                List.of(rule("Alert", "cpu or vector(1)", Map.of(), "ok"))));
        assertThrows(IllegalArgumentException.class, () -> exporter.export("group",
                List.of(rule("Alert", "cpu", Map.of("zone=\"a\",other", "b"), "ok"))));
    }
}
