package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.http.HttpResponse;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.security.SecurityMetadataVerifier;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 共享/独占 IO 资源真实 socket 生命周期，覆盖关闭及创建失败。 @author zn */
class NettyIoResourcesTest {
    /** 统一测试传输预算。 */
    private static final ServerOptions OPTIONS = ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1);

    @Test void stoppingOneServerClosesItsChildrenWithoutStoppingSharedPeers() throws Exception {
        try (var io = NettyIoResources.open(OPTIONS)) {
            var first = echo(OPTIONS, io);
            var second = echo(OPTIONS, io);
            try {
                first.start();
                first.start();
                second.start();
                try (var clientA = socket(first.boundPort()); var clientB = socket(second.boundPort())) {
                    exchange(clientA);
                    exchange(clientB);
                    first.stop();
                    first.stop();
                    assertEquals(-1, clientA.getInputStream().read());
                    assertFalse(io.snapshot().closing());
                    exchange(clientB);
                    first.start();
                    try (var replacement = socket(first.boundPort())) { exchange(replacement); }
                }
            } finally { first.stop(); second.stop(); }
        }
    }

    @Test void httpAndUdpBorrowTheSameCompatibleResourceWithoutClosingIt() throws Exception {
        try (var io = NettyIoResources.open(OPTIONS)) {
            var http = new NettyHttpServer(ServerOptions.http("127.0.0.1", 0).withIoThreads(1, 1),
                    request -> CompletableFuture.completedFuture(HttpResponse.text(200, "ok")), Runnable::run,
                    SecurityMetadataVerifier.failClosed(), io);
            var udp = new NettyUdpServer(ServerOptions.udp("127.0.0.1", 0).withIoThreads(1, 1),
                    new ZeroBinaryFrameCodec(), (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                    new ConnectionListener() { }, Runnable::run, io);
            try {
                http.start();
                udp.start();
                try (var client = socket(http.boundPort())) {
                    client.getOutputStream().write("GET / HTTP/1.1\r\nHost: localhost\r\n\r\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                    assertEquals('H', client.getInputStream().read());
                    udp.stop();
                    assertFalse(io.snapshot().closing());
                }
                http.stop();
                assertFalse(io.snapshot().closing());
            } finally { http.stop(); udp.stop(); }
        }
    }

    @Test void bindFailureAndInterruptedStartDoNotCloseBorrowedGroups() throws Exception {
        try (var io = NettyIoResources.open(OPTIONS); var occupied = new java.net.ServerSocket(0)) {
            var failed = echo(ServerOptions.tcp("127.0.0.1", occupied.getLocalPort()).withIoThreads(1, 1), io);
            assertThrows(ZeroException.class, failed::start);
            failed.stop();
            assertFalse(io.snapshot().closing());
            var interrupted = echo(OPTIONS, io);
            Thread.currentThread().interrupt();
            try {
                assertThrows(ZeroException.class, interrupted::start);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); interrupted.stop(); }
            var recovery = echo(OPTIONS, io);
            try { recovery.start(); try (var client = socket(recovery.boundPort())) { exchange(client); } }
            finally { recovery.stop(); }
        }
    }

    @Test void ioThreadStopAndResourceCloseNeverWaitForThemselves() throws Exception {
        var io = NettyIoResources.open(OPTIONS);
        var server = echo(OPTIONS, io);
        try {
            server.start();
            try (var client = socket(server.boundPort())) {
                exchange(client);
                io.workers().submit(server::stop).get(3, TimeUnit.SECONDS);
                assertEquals(-1, client.getInputStream().read());
            }
            io.workers().submit(io::close).get(3, TimeUnit.SECONDS);
            io.termination().toCompletableFuture().get(5, TimeUnit.SECONDS);
            assertTrue(io.snapshot().terminated());
            assertThrows(ZeroException.class, server::start);
        } finally { server.stop(); io.close(); }
    }

    @Test void pureUdpResourcesRejectTcpAndRepeatedCloseIsSafe() {
        var io = NettyIoResources.open(ServerOptions.udp("127.0.0.1", 0).withIoThreads(1, 1));
        try {
            assertEquals(0, io.snapshot().bossThreads());
            assertThrows(ZeroException.class, () -> echo(OPTIONS, io).start());
        } finally { io.close(); io.close(); }
        assertTrue(io.snapshot().terminated());
    }

    private static NettyTcpServer echo(final ServerOptions options, final NettyIoResources io) {
        return new NettyTcpServer(options, new ZeroBinaryFrameCodec(),
                (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                new ConnectionListener() { }, Runnable::run, null, null, io);
    }

    private static Socket socket(final int port) throws Exception {
        var socket = new Socket("127.0.0.1", port);
        socket.setSoTimeout(3000);
        return socket;
    }

    private static void exchange(final Socket socket) throws Exception {
        var codec = new ZeroBinaryFrameCodec();
        byte[] encoded = codec.encode(new ProtocolFrame(1, 1, 0, null, new byte[]{1, 2, 3}));
        var output = new DataOutputStream(socket.getOutputStream());
        output.writeInt(encoded.length);
        output.write(encoded);
        output.flush();
        var input = new DataInputStream(socket.getInputStream());
        byte[] response = input.readNBytes(input.readInt());
        assertEquals(3, codec.decode(response).payloadLength());
    }
}
