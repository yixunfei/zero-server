package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.net.NetworkRuntime;
import group.zn.zero.runtime.production.ProductionAssembly;
import group.zn.zero.starter.LocalRuntime;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 可选 IO 能力能用于两种组合根，最小 starter 不自动创建网络线程。 @author zn */
class OptionalNetworkIoTest {
    @Test void localStarterCanExplicitlySelectAndOwnIoResources() {
        var options = ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1);
        var builder = LocalRuntime.builder().register(NetworkRuntime.ioResources(options))
                .override(NetworkRuntime.IO_RESOURCES, StandardRuntimeCapabilityModel.NETWORK_IO_PROVIDER)
                .require(NetworkRuntime.IO_RESOURCES);
        builder.diagnose();
        var runtime = builder.build();
        var io = runtime.require(NetworkRuntime.IO_RESOURCES);
        assertFalse(io.snapshot().closing());
        runtime.close();
        assertTrue(io.snapshot().terminated());
    }

    @Test void productionCompositionInstallsTheSameOptionalModuleWithoutExternalServices() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null)
                .install(NetworkRuntime.ioModule(ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1)));
        assembly.diagnose();
        var runtime = assembly.build();
        var io = runtime.require(NetworkRuntime.IO_RESOURCES);
        assertFalse(io.snapshot().closing());
        runtime.close();
        assertTrue(io.snapshot().terminated());
    }
}
