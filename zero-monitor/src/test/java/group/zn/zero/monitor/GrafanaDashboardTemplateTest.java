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
