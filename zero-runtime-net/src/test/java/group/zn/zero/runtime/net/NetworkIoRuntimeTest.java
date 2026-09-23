package group.zn.zero.runtime.net;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.ComponentCatalog;
import group.zn.zero.runtime.assembly.RuntimeAssembler;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 验证可选 IO 能力在 planning/build/rollback/stop 中的资源拥有权。 @author zn */
class NetworkIoRuntimeTest {
    /** 应用服务器能力。 */
    private static final ComponentKey<NettyTcpServer> SERVER = ComponentKey.single("test.net.server", NettyTcpServer.class);
    /** 显式测试预算。 */
    private static final ServerOptions OPTIONS = ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1);

    @Test void planningDoesNotCreateAndRuntimeStopsServerBeforeOwnedResources() {
        var resource = new AtomicReference<NettyIoResources>();
        var builder = builder(resource, false);
        builder.diagnose();
        assertTrue(resource.get() == null);
        var runtime = builder.build();
        try {
            runtime.start();
            assertTrue(runtime.require(SERVER).running());
            assertFalse(resource.get().snapshot().closing());
        } finally { runtime.close(); }
        assertTrue(resource.get().snapshot().terminated());
        assertFalse(runtime.require(SERVER).running());
    }

    @Test void downstreamCreateFailureRollsBackIoGroups() {
        var resource = new AtomicReference<NettyIoResources>();
        assertThrows(RuntimeException.class, () -> builder(resource, true).build());
        assertTrue(resource.get().snapshot().terminated());
    }

    private static RuntimeAssembler.Builder builder(final AtomicReference<NettyIoResources> captured, final boolean fail) {
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("test.net.server"))
                .provide(SERVER).require(NetworkRuntime.IO_RESOURCES).build(), context -> {
                    var io = context.require(NetworkRuntime.IO_RESOURCES);
                    captured.set(io);
                    if (fail) throw new IllegalStateException("downstream creation failed");
                    var server = new NettyTcpServer(OPTIONS, new ZeroBinaryFrameCodec(),
                            (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                            new ConnectionListener() { }, Runnable::run, null, null, io);
                    return ComponentContribution.builder().bind(SERVER, server).lifecycle(server).build();
                });
        var catalog = ComponentCatalog.builder().register("test", NetworkRuntime.ioResources(OPTIONS))
                .register("test", provider).build();
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local()).require(SERVER)
                .select(SERVER, provider.descriptor().id(), "test")
                .select(NetworkRuntime.IO_RESOURCES,
                        group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel.NETWORK_IO_PROVIDER, "test");
    }
}
