package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** Production 默认 IO、明确错误提示和独立预算的上手回归。 */
class ProductionOnboardingTest {
    /** 默认装配与诊断计划一致，IO 由 runtime 拥有并在关闭时释放。 */
    @Test
    void defaultRemoteIoIsOwnedAndDirectOverrideFailsDuringDiagnosis() {
        var config = new MapZeroConfig(Map.of());
        var assembly = ProductionAssembly.builder("external-test", config);
        var plan = assembly.diagnosis().plan().orElseThrow();
        ZeroRuntimeExecutors executors;
        try (var runtime = assembly.build()) {
            assertEquals(plan, runtime.plan());
            executors = runtime.require(RuntimeBasics.EXECUTORS);
            assertFalse(executors.remoteIoMayInline());
        }
        assertThrows(RejectedExecutionException.class, () -> executors.remoteIoExecutor().execute(() -> { }));
        var key = ComponentKey.single("test.adapter", String.class);
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("app.adapter"))
                .provide(key).require(RuntimeBasics.EXECUTORS).build(), context ->
                ComponentContribution.builder().bind(key, "unused").build());
        var invalid = ProductionAssembly.builder("external-test", config, ZeroRuntimeExecutors.direct())
                .install(context -> new ProductionModule(List.of(provider), List.of(), List.of()));
        assertEquals(ProductionAdapterErrorCode.REMOTE_IO_EXECUTOR_REQUIRED,
                assertThrows(ProductionAdapterException.class, invalid::diagnosis).errorCode());
    }

    /** 启动预算不影响运行超时；配置优先级、环境别名和错误脱敏保持一致。 */
    @Test
    void runtimeSettingsAreIndependentAndStrict() {
        var resolver = new ProductionConfigResolver(new MapZeroConfig(Map.of(
                "zero.adapter.startup-timeout-millis", "1",
                "zero.adapter.redis.operation-timeout-millis", "1200",
                "zero.adapter.redis.max-in-flight", "7")), key -> null,
                key -> key.equals("ZERO_ADAPTER_REDIS_PROBE_INTERVAL_MILLIS") ? "500" : null);
        var settings = RuntimeAdapterSettings.resolve(resolver, "redis", 10000);
        assertEquals(Duration.ofMillis(1200), settings.operationTimeout());
        assertEquals(7, settings.maxInFlight());
        assertEquals(Duration.ofMillis(500), settings.probeInterval());
        var invalid = new ProductionConfigResolver(new MapZeroConfig(Map.of(
                "zero.adapter.redis.max-in-flight", "secret-invalid")), key -> null, key -> null);
        var failure = assertThrows(ProductionAdapterException.class,
                () -> RuntimeAdapterSettings.resolve(invalid, "redis", 10000));
        assertFalse(failure.toString().contains("secret-invalid"));
    }
}
