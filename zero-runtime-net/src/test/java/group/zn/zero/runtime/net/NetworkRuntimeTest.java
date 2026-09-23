package group.zn.zero.runtime.net;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class NetworkRuntimeTest {
    @Test
    void independentModuleCarriesResolvedSettingsIntoTheCreatedLifecycle() {
        var config = new MapZeroConfig(Map.of(
                ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true",
                ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER, "application-gateway",
                ZeroProductionRuntimeConfigKeys.NETWORK_HANDSHAKE_TIMEOUT_MILLIS, "4321",
                ZeroProductionRuntimeConfigKeys.NETWORK_MAX_INBOUND_FRAMES, "77"));
        try (var executors = ZeroRuntimeExecutors.localPrototype("network-module", 1)) {
            var assembly = ProductionAssembly.builder("production", config, executors)
                    .configSourceLookups(key -> null, key -> null)
                    .install(LogRuntime.module())
                    .install(MonitorRuntimeComponent.module())
                    .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null));
            assembly.diagnose();
            try (var runtime = assembly.build()) {
                var settings = runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).config();
                assertEquals("application-gateway", settings.listener());
                assertEquals(Duration.ofMillis(4321), settings.handshakeTimeout());
                assertEquals(77, settings.maxInboundFrames());
            }
        }
    }
}
