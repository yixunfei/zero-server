package group.zn.zero.runtime.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.net.kcp.KcpSnapshot;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.production.ProductionAssembly;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 可选装配、隔离配置、启动失败回滚及低基数监控。 @author zn */
class KcpRuntimeTest {
    /** 生产组合器直接安装模块，在受管后台自动采样，无需业务手动调用 telemetry。 */
    @Test @Timeout(10) void samplesMetricsThroughProductionAssembly() throws Exception {
        var assembly = ProductionAssembly.builder(
                "standalone", new MapZeroConfig(Map.of()), ZeroRuntimeExecutors.localPrototype("kcp-monitor", 1))
                .install(MonitorRuntimeComponent.module())
                .install(KcpRuntime.module("game", "127.0.0.1", 0, KcpProfile.BALANCED,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of(frame))));
        assembly.plan();
        try (var runtime = assembly.build(); var socket = new java.net.DatagramSocket()) {
            runtime.start();
            var server = runtime.require(KcpRuntime.server("game"));
            var registry = runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME).registry();
            socket.send(new java.net.DatagramPacket(new byte[1], 1,
                    java.net.InetAddress.getLoopbackAddress(), server.boundPort()));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
            while (System.nanoTime() < deadline && registry.samples().stream().noneMatch(sample ->
                    sample.name().equals("zero_kcp_received_total") && sample.value() == 1)) {
                new CompletableFuture<Void>().completeOnTimeout(null, 20, TimeUnit.MILLISECONDS).join();
            }
            assertTrue(registry.samples().stream().anyMatch(sample -> sample.name().equals("zero_kcp_received_total")
                    && sample.value() == 1 && sample.labels().equals(Map.of("listener", "game"))));
            assertEquals(1, server.snapshot().rejectedDatagrams());
        }
    }
    /** 两个场景独立安装，配置只覆盖指定监听器。 */
    @Test void installsNamedProfilesWithOverrides() {
        var config = new MapZeroConfig(Map.of("zero.kcp.game.profile", "MOBILE", "zero.kcp.game.maxSessions", "17",
                "zero.kcp.game.protectionId", "2", "zero.kcp.game.fecWireId", "1",
                "zero.kcp.game.pathEnabled", "true"));
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(config, () -> ZeroRuntimeExecutors.localPrototype("kcp-runtime", 1)))
                .install(KcpRuntime.module("game", "127.0.0.1", 0, KcpProfile.BALANCED,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of(frame))))
                .install(KcpRuntime.module("lobby", "127.0.0.1", 0, KcpProfile.LOW_FREQUENCY,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of(frame))));
        composition.diagnose();
        try (var runtime = composition.build()) {
            runtime.start();
            var game = runtime.require(KcpRuntime.server("game"));
            var lobby = runtime.require(KcpRuntime.server("lobby"));
            assertEquals(KcpProfile.MOBILE, game.configuration().profile());
            assertEquals(17, game.configuration().maxSessions());
            assertEquals(2, game.configuration().transport().protectionId());
            assertEquals(1, game.configuration().transport().fec().wireId());
            assertTrue(game.configuration().transport().paths().enabled());
            assertEquals(KcpProfile.LOW_FREQUENCY, lobby.configuration().profile());
            assertNotEquals(game.boundPort(), lobby.boundPort());
        }
    }
    /** 非法跨参数组合在 socket 创建前失败。 */
    @Test void rejectsInvalidConfigurationAndDirectExecutors() {
        var config = new MapZeroConfig(Map.of("zero.kcp.game.maxFrameBytes", "999999"));
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(config, () -> ZeroRuntimeExecutors.localPrototype("kcp-bad", 1)))
                .install(KcpRuntime.module("game", "127.0.0.1", 0, KcpProfile.BALANCED,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of())));
        assertThrows(RuntimeException.class, composition::build);
        var direct = RuntimeBasics.builder().install(KcpRuntime.module("game", "127.0.0.1", 0, KcpProfile.BALANCED,
                (connection, frame) -> CompletableFuture.completedFuture(List.of())));
        assertThrows(RuntimeException.class, direct::build);
    }
    /** 指标只含命名监听器和固定原因，计数为绝对采样值。 */
    @Test void recordsIsolatedMetrics() {
        var registry = new InMemoryMetricRegistry();
        var first = new KcpTelemetry("game", registry);
        var second = new KcpTelemetry("lobby", registry);
        var snapshot = new KcpSnapshot(2, 1, 10, 9, 1, 0, 5, 4, 200, 100, Map.of("reject.oversize", 1L));
        first.accept(snapshot); second.accept(snapshot);
        assertTrue(registry.samples().stream().anyMatch(sample -> sample.name().equals("zero_kcp_connected") && sample.value() == 1));
        assertTrue(registry.samples().stream().allMatch(sample -> sample.labels().keySet().stream()
                .allMatch(key -> key.equals("listener") || key.equals("reason"))));
        assertEquals(2, registry.samples().stream().map(sample -> sample.labels().get("listener")).distinct().count());
    }
}
