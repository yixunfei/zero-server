package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.config.ConfigValidationStatus;
import group.zn.zero.runtime.net.NetworkRuntime;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Production network provider 的选择、typed config 与依赖安全契约测试。 */
class ProductionNetworkProviderTest {

    @Test
    void selectedProviderShouldBindLifecycleWithTypedDefaults() {
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("network-provider");
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(config(Map.of(
                        ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true",
                        ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER, " ")))
                .executors(executors)
                .networkPolicy((connection, frame) -> NetworkAdmissionDecision.allow())
                .build();
        try {
            ProductionNetworkLifecycle lifecycle = runtime.require(
                    NetworkRuntime.NETWORK_LIFECYCLE);
            assertEquals(ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_LISTENER, lifecycle.config().listener());
            assertEquals("ProductionIpConnectionRateLimiter", lifecycle.rateLimiter().getClass().getSimpleName());
            var metadata = runtime.plan().config().stream()
                    .filter(item -> item.owner().equals(group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE))
                    .toList();
            assertEquals(10, metadata.size());
            assertTrue(metadata.stream().noneMatch(item -> item.sensitive()));
            assertTrue(metadata.stream().allMatch(item ->
                    item.validationStatus() == ConfigValidationStatus.DEFAULTED));
        } finally {
            runtime.close();
        }
    }

    @Test
    void enabledNetworkShouldRequireExplicitPolicy() {
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> ZeroProductionRuntimeFactory.externalTestBuilder(config(Map.of(
                                ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "TRUE")))
                        .diagnose());

        assertEquals(ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_SELECTION, failure.failurePhase());
    }

    @Test
    void selectedNetworkShouldRejectInlineRemoteIoExecutor() {
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                () -> ZeroProductionRuntimeFactory.externalTestBuilder(config(Map.of(
                                ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true")))
                        .networkPolicy((connection, frame) -> NetworkAdmissionDecision.allow())
                        .build());

        assertEquals(ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
    }

    @Test
    void networkMustUseTheReplacementAndLeaveUnusedBaseExecutorsUncreated() {
        AtomicInteger creations = new AtomicInteger();
        try (var executors = ZeroRuntimeExecutors.localPrototype("replacement")) {
            var assembly = networkAssembly().base(RuntimeBasics.module(config(Map.of()), () -> {
                creations.incrementAndGet();
                return ZeroRuntimeExecutors.direct();
            })).configure(composition -> composition.replace(RuntimeBasics.EXECUTORS, executors));
            assembly.diagnose();
            try (var runtime = assembly.build()) {
                assertSame(executors, runtime.require(RuntimeBasics.EXECUTORS));
                assertTrue(runtime.optional(NetworkRuntime.NETWORK_LIFECYCLE).isPresent());
                assertEquals(0, creations.get());
            }
        }
    }

    @Test
    void networkMustAllowLazyManagedBaseWithoutAllocatingDuringDiagnosis() {
        AtomicInteger creations = new AtomicInteger();
        var assembly = networkAssembly().base(RuntimeBasics.module(config(Map.of()), () -> {
            creations.incrementAndGet();
            return ZeroRuntimeExecutors.localPrototype("lazy-base");
        }));
        assembly.diagnose();
        assertEquals(0, creations.get());
        try (var runtime = assembly.build()) {
            assertFalse(runtime.require(RuntimeBasics.EXECUTORS).remoteIoMayInline());
            assertEquals(1, creations.get());
        }
    }

    @Test
    void replacingManagedExecutorsWithInlineExecutorsMustStillFail() {
        try (var executors = ZeroRuntimeExecutors.localPrototype("unused-managed")) {
            var assembly = ProductionAssembly.builder("external-test", config(Map.of(
                            ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true")), executors)
                    .configSourceLookups(key -> null, key -> null)
                    .install(LogRuntime.module()).install(MonitorRuntimeComponent.module())
                    .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null))
                    .configure(composition -> composition.replace(RuntimeBasics.EXECUTORS, ZeroRuntimeExecutors.direct()));
            assertThrows(ProductionAdapterException.class, assembly::build);
        }
    }

    private ProductionAssembly networkAssembly() {
        return ProductionAssembly.builder(config(Map.of(ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true")))
                .configSourceLookups(key -> null, key -> null)
                .install(LogRuntime.module()).install(MonitorRuntimeComponent.module())
                .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null));
    }

    @Test
    void customLimiterShouldPreserveIgnoredDefaultLimiterConfigSemantics() {
        NetworkRateLimiter customLimiter = connection -> false;
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("network-custom-limiter");
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(config(Map.of(
                        ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true",
                        ZeroProductionRuntimeConfigKeys.NETWORK_PER_IP_PERMITS_PER_SECOND, "not-an-integer")))
                .executors(executors)
                .networkPolicy((connection, frame) -> NetworkAdmissionDecision.allow())
                .networkRateLimiter(customLimiter)
                .build();
        try {
            assertSame(
                    customLimiter,
                    runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).rateLimiter());
        } finally {
            runtime.close();
        }
    }

    @Test
    void whitespaceAroundEnabledShouldRemainDisabled() {
        ZeroProductionRuntime runtime = ZeroProductionRuntimeFactory.externalTestBuilder(config(Map.of(
                        ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, " true ")))
                .build();
        try {
            assertFalse(runtime.optional(NetworkRuntime.NETWORK_LIFECYCLE).isPresent());
        } finally {
            runtime.close();
        }
    }

    private MapZeroConfig config(final Map<String, String> values) {
        return new MapZeroConfig(values);
    }
}
