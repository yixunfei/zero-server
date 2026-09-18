package group.zn.zero.monitor;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 样本历史有界且按记录顺序淘汰。 @author zn */
class MetricReportAuditTest {
    /** 满容量保留最近样本并显式统计淘汰。 */
    @Test void sampleRetentionIsBoundedAndObservable() {
        InMemoryMetricRegistry registry = new InMemoryMetricRegistry(new MetricLabelPolicy() { }, 3);
        registry.register(new MetricDefinition("audit_total", "test", "count", List.of()));
        for (int i = 0; i < 10; i++) registry.record(new MetricSample("audit_total", i, Map.of(), Instant.EPOCH));
        assertEquals(3, registry.samples().size());
        assertEquals(7, registry.droppedSamples());
        assertEquals(7D, registry.samples().getFirst().value());
        assertEquals(3, registry.exportText().lines().count());
    }
}
