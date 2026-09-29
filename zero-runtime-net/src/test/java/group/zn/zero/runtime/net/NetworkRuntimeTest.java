package group.zn.zero.runtime.net;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.ConnectionLifecycleResult;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkRateLimitScope;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.net.netty.NettyConnection;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 最小装配、配置边界与显式观测接入回归。 @author zn */
class NetworkRuntimeTest {
    /** 网络模块不依赖日志和监控能力即可完成 planning 与创建。 */
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
                    .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null));
            assembly.diagnose();
            try (var runtime = assembly.build()) {
                var settings = runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).config();
                assertNull(runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).securityChain());
                assertEquals("application-gateway", settings.listener());
                assertEquals(Duration.ofMillis(4321), settings.handshakeTimeout());
                assertEquals(77, settings.maxInboundFrames());
            }
        }
    }

    /** 未注入限流或安全链时保留用户 policy，且无隐式 IP 或 frame 阈值。 */
    @Test
    void omittedStrategiesShouldPreservePolicyAndPermitTraffic() {
        ProductionNetworkPolicy policy = (connection, frame) -> NetworkAdmissionDecision.allow();
        try (var executors = ZeroRuntimeExecutors.localPrototype("minimal-network", 1);
                var runtime = assembly(executors, Map.of())
                        .install(NetworkRuntime.module(policy, null)).build()) {
            var lifecycle = runtime.require(NetworkRuntime.NETWORK_LIFECYCLE);
            assertSame(policy, lifecycle.policy());
            assertNull(lifecycle.securityChain());
            assertFalse(lifecycle.config().heartbeatEnabled());
            assertTrue(runtime.optional(LogRuntime.LOG_APPENDER).isEmpty());
            assertTrue(runtime.optional(MonitorRuntimeComponent.MONITOR_RUNTIME).isEmpty());
            var channel = new EmbeddedChannel();
            try {
                var connection = new NettyConnection("minimal-test", channel);
                var frame = new ProtocolFrame(1, 1, 0, null, new byte[0]);
                for (int index = 0; index < 100; index++) {
                    assertTrue(lifecycle.rateLimiter().allowConnection(connection));
                    assertTrue(lifecycle.rateLimiter().allowAdmissionFrame(connection, frame));
                    assertTrue(lifecycle.rateLimiter().allowFrame(connection, frame));
                }
            } finally {
                channel.finishAndReleaseAll();
            }
        }
    }

    /** 安装日志和监控不隐式注册网络指标或将网络事件写入遥测下游。 */
    @Test
    void installedTelemetryModulesShouldNotEnableNetworkObserver() {
        try (var executors = ZeroRuntimeExecutors.localPrototype("network-no-observer", 1);
                var runtime = assembly(executors, Map.of())
                        .install(LogRuntime.module()).install(MonitorRuntimeComponent.module())
                        .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null))
                        .build()) {
            var registry = runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME).registry();
            var before = registry.samples();
            runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).observer().onEvent(observation());
            assertEquals(before, registry.samples());
        }
    }

    /** 自定义 observer 不需要框架日志或指标模块。 */
    @Test
    void customObserverShouldWorkWithoutTelemetryModules() {
        AtomicInteger events = new AtomicInteger();
        ConnectionLifecycleObserver observer = observation -> events.incrementAndGet();
        try (var executors = ZeroRuntimeExecutors.localPrototype("network-custom-observer", 1);
                var runtime = assembly(executors, Map.of())
                        .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(),
                                null, null, observer)).build()) {
            var actual = runtime.require(NetworkRuntime.NETWORK_LIFECYCLE).observer();
            assertSame(observer, actual);
            actual.onEvent(observation());
            assertEquals(1, events.get());
            assertTrue(runtime.optional(LogRuntime.LOG_APPENDER).isEmpty());
            assertTrue(runtime.optional(MonitorRuntimeComponent.MONITOR_RUNTIME).isEmpty());
        }
    }

    /** 非法开关在 planning 阶段失败，不静默按 false 处理。 */
    @ParameterizedTest
    @ValueSource(strings = {"tru", "1", " true "})
    void invalidHeartbeatFlagShouldFailDuringDiagnosis(final String value) {
        try (var executors = ZeroRuntimeExecutors.localPrototype("network-invalid-flag", 1)) {
            var assembly = assembly(executors, Map.of(
                    ZeroProductionRuntimeConfigKeys.NETWORK_HEARTBEAT_ENABLED, value))
                    .install(NetworkRuntime.module((connection, frame) -> NetworkAdmissionDecision.allow(), null));
            var failure = assertThrows(ProductionAdapterException.class, assembly::diagnose);
            assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
        }
    }

    private static ProductionAssembly assembly(
            final ZeroRuntimeExecutors executors, final Map<String, String> settings) {
        var values = new java.util.HashMap<>(settings);
        values.put(ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED, "true");
        return ProductionAssembly.builder("production", new MapZeroConfig(values), executors)
                .configSourceLookups(key -> null, key -> null);
    }

    private static ConnectionLifecycleObservation observation() {
        return new ConnectionLifecycleObservation(Instant.EPOCH, "test", "tcp", "trace", "connection", "peer",
                ConnectionLifecycleState.ACCEPTED, ConnectionLifecycleEventType.CHANNEL_ACCEPTED,
                ConnectionLifecycleResult.OBSERVED, ConnectionRejectionReason.NONE, NetworkRateLimitScope.NONE,
                null, 0L, 0);
    }
}
