package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Grafana dashboard 模板测试。
 *
 * @author zn
 */
class GrafanaDashboardTemplateTest {
    /** 重复指标也有独立面板 ID；配置不能注入 PromQL 或生成非法 JSON。 */
    @Test void validatesIdentifiersAndAssignsIndependentPanelIds() {
        var template = new GrafanaDashboardTemplate();
        String json = template.createDashboard("duplicate", List.of("metric", "metric"));
        assertTrue(json.contains("\"id\":1"));
        assertTrue(json.contains("\"id\":2"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> template.createDashboard("bad\ntext", List.of("metric")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> template.createDashboard("valid", List.of("metric or vector(1)")));
    }


    /**
     * 验证 dashboard JSON 包含标题和指标表达式。
     */
    @Test
    void dashboardShouldContainTitleAndMetricTargets() {
        String json = new GrafanaDashboardTemplate().createDashboard(
                "zeroServer",
                List.of(SystemMetricCollector.JVM_MEMORY_USED, SystemMetricCollector.SYSTEM_CPU_LOAD));

        assertTrue(json.contains("\"title\":\"zeroServer\""));
        assertTrue(json.contains(SystemMetricCollector.JVM_MEMORY_USED));
        assertTrue(json.contains(SystemMetricCollector.SYSTEM_CPU_LOAD));
    }
}
