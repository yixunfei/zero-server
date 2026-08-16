package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.runtime.config.ConfigValidationStatus;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import java.util.Map;
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
                    ProductionRuntimeCapabilities.NETWORK_LIFECYCLE);
            assertEquals(ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_LISTENER, lifecycle.config().listener());
            assertInstanceOf(ProductionIpConnectionRateLimiter.class, lifecycle.rateLimiter());
            var metadata = runtime.plan().config().stream()
                    .filter(item -> item.owner().equals(ProductionNetworkProvider.ID))
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

        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_NETWORK_LIFECYCLE, failure.adapterName());
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

        assertEquals(ZeroProductionRuntimeBuilder.ADAPTER_NETWORK_LIFECYCLE, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.CONFIG_SELECTION, failure.failurePhase());
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
                    runtime.require(ProductionRuntimeCapabilities.NETWORK_LIFECYCLE).rateLimiter());
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
            assertFalse(runtime.optional(ProductionRuntimeCapabilities.NETWORK_LIFECYCLE).isPresent());
        } finally {
            runtime.close();
        }
    }

    private MapZeroConfig config(final Map<String, String> values) {
        return new MapZeroConfig(values);
    }
}
