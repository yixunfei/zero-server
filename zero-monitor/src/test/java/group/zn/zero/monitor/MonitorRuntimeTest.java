package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * 监控运行时门面测试。
 *
 * @author zn
 */
class MonitorRuntimeTest {

    /**
     * 验证默认运行时可以一键采样并导出。
     */
    @Test
    void runtimeShouldCollectAndExport() {
        MonitorRuntime runtime = MonitorRuntime.createDefault();

        MonitorCollectionResult result = runtime.collectOnce();
        String prometheus = runtime.exportPrometheus();
        String dashboard = runtime.exportGrafanaDashboard(
                "zeroServer",
                List.of(SystemMetricCollector.JVM_THREAD_COUNT));

        assertNotNull(result.systemMetricReport());
        assertNotNull(result.alertEvents());
        assertTrue(result.systemMetricReport().attemptedProbeCount() > 0);
        assertTrue(!runtime.registry().samples().isEmpty());
        assertTrue(prometheus.contains(SystemMetricCollector.JVM_THREAD_COUNT));
        assertTrue(dashboard.contains(SystemMetricCollector.JVM_THREAD_COUNT));
    }
}
