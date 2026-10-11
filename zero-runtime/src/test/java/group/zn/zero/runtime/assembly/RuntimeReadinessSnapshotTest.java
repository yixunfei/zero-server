package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 读取健康只获取内存快照，不执行探测；未运行的 runtime 不报告就绪。 */
class RuntimeReadinessSnapshotTest {
    /** 动态状态、停止状态和源异常均可由中立门面安全读取。 */
    @Test
    void readinessFollowsCachedStateAndNeverReportsClosedRuntimeReady() {
        var key = ComponentKey.single("test.health", String.class);
        var result = new AtomicReference<>(HealthResult.healthy("ready"));
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("app.health"))
                .provide(key).health(HealthPhase.READINESS).build(), context -> ComponentContribution.builder()
                .bind(key, "test").healthSnapshot(HealthPhase.READINESS, result::get).build());
        try (var runtime = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeModule.of("app.health", List.of(provider), key)).build()) {
            assertEquals(HealthStatus.UNKNOWN, runtime.healthSnapshot().components().getFirst().result().status());
            runtime.start();
            assertEquals(HealthStatus.HEALTHY, runtime.healthSnapshot().components().getFirst().result().status());
            result.set(new HealthResult(HealthStatus.UNHEALTHY, "remote-io-failed"));
            assertEquals(HealthStatus.UNHEALTHY, runtime.healthSnapshot().components().getFirst().result().status());
            result.set(null);
            assertEquals("health-snapshot-failed", runtime.healthSnapshot().components().getFirst().result().code());
            runtime.stop();
            result.set(HealthResult.healthy("ready"));
            assertEquals(HealthStatus.UNKNOWN, runtime.healthSnapshot().components().getFirst().result().status());
        }
    }
}
