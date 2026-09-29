package group.zn.zero.runtime.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.kcp.KcpAlgorithms;
import group.zn.zero.net.kcp.KcpDatagramProtection;
import group.zn.zero.net.kcp.KcpFecOptions;
import group.zn.zero.net.kcp.KcpOptions;
import group.zn.zero.net.kcp.KcpPathOptions;
import group.zn.zero.net.kcp.KcpProfile;
import group.zn.zero.net.kcp.KcpProtectionStrategies;
import group.zn.zero.net.kcp.KcpSessionServices;
import group.zn.zero.net.kcp.KcpTransportOptions;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 场景切换与传输策略独立，私有算法通过正式 runtime 入口装配。 @author zn */
class KcpTransportConfigurationTest {
    /** 切换 MOBILE 不降低显式 AES/FEC/路径策略；关闭 FEC 自动恢复合法 NONE 参数。 */
    @Test void preservesTransportWhileSwitchingWorkload() {
        var policy = KcpTransportOptions.of(3, KcpFecOptions.reedSolomon(4, 2), KcpPathOptions.validated());
        var defaults = KcpOptions.defaults().toBuilder().transport(policy).build();
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of(
                        "zero.kcp.game.profile", "MOBILE", "zero.kcp.game.fecWireId", "0")),
                        () -> ZeroRuntimeExecutors.localPrototype("kcp-policy", 1)))
                .install(KcpRuntime.module("game", ServerOptions.kcp("127.0.0.1", 0), defaults,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of()), new ConnectionListener() { }));
        try (var runtime = composition.build()) {
            runtime.start();
            var actual = runtime.require(KcpRuntime.server("game")).configuration();
            assertEquals(KcpProfile.MOBILE, actual.profile());
            assertEquals(3, actual.transport().protectionId());
            assertEquals(KcpFecOptions.none(), actual.transport().fec());
            assertTrue(actual.transport().paths().enabled());
            assertTrue(actual.requireControlTls());
        }
    }
    /** 未使用全局注册；应用提供的私有工厂在 runtime 创建时可见。 */
    @Test void acceptsExplicitCustomProtectionRegistry() {
        var custom = new KcpDatagramProtection() {
            @Override public int wireId() { return 256; }
            @Override public int tagBytes() { return 16; }
            @Override public boolean authenticated() { return true; }
            @Override public boolean encrypted() { return true; }
            @Override public Context create(final byte[] key) { return KcpProtectionStrategies.aesGcm().create(key); }
        };
        var defaults = KcpOptions.defaults().toBuilder()
                .transport(KcpTransportOptions.of(256, KcpFecOptions.none(), KcpPathOptions.fixed())).build();
        var composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeBasics.module(new MapZeroConfig(Map.of()),
                        () -> ZeroRuntimeExecutors.localPrototype("kcp-custom", 1)))
                .install(KcpRuntime.module("game", ServerOptions.kcp("127.0.0.1", 0), defaults,
                        (connection, frame) -> CompletableFuture.completedFuture(List.of()), new ConnectionListener() { },
                        KcpSessionServices.local(), KcpAlgorithms.defaults().withProtection(custom)));
        try (var runtime = composition.build()) {
            runtime.start();
            assertEquals(256, runtime.require(KcpRuntime.server("game")).configuration().transport().protectionId());
        }
    }
}
