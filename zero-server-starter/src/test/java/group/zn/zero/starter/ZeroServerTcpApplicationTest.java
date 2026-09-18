package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFactory;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Explicit TCP application lifecycle tests. */
class ZeroServerTcpApplicationTest {
    @Test
    void startProbeStopIsIdempotentAndReleasesListener() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        IServer server = ServerFactory.tcp(ServerOptions.tcp("127.0.0.1", 0),
                (connection, frame) -> CompletableFuture.completedFuture(List.of()), executor);
        GameRuntime runtime = LocalRuntime.create(new MapZeroConfig(Map.of("zero.mode", "test")), new InMemoryLogSink());
        ZeroServerTcpApplication application = new ZeroServerTcpApplication(runtime, server);

        try {
            application.start();
            assertTrue(application.running());
            assertTrue(application.probe());
            application.start();
            application.stop();
            application.stop();
            assertFalse(application.running());
            assertFalse(server.running());
            assertFalse(application.probe());
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void serverStartFailureStopsRuntime() {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        IServer first = ServerFactory.tcp(ServerOptions.tcp("127.0.0.1", 0),
                (connection, frame) -> CompletableFuture.completedFuture(List.of()), executor);
        first.start();
        IServer second = ServerFactory.tcp(ServerOptions.tcp("127.0.0.1", Integer.parseInt(first.bindAddress().split(":")[1])),
                (connection, frame) -> CompletableFuture.completedFuture(List.of()), executor);
        GameRuntime runtime = LocalRuntime.create(new MapZeroConfig(Map.of("zero.mode", "test")), new InMemoryLogSink());
        ZeroServerTcpApplication application = new ZeroServerTcpApplication(runtime, second);
        try {
            org.junit.jupiter.api.Assertions.assertThrows(java.net.BindException.class, application::start);
            assertFalse(application.running());
        } finally {
            first.stop();
            if (second.running()) {
                second.stop();
            }
            executor.shutdownNow();
        }
    }
}
