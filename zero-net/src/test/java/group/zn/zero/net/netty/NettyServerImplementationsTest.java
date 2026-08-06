package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.http.HttpResponse;
import group.zn.zero.net.kcp.UnsupportedKcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * Netty server 实现测试。
 *
 * @author zn
 */
class NettyServerImplementationsTest {

    /**
     * 验证 TCP 服务端可以启动、连接、收发协议帧并关闭。
     */
    @Test
    void tcpServerShouldStartExchangeFrameAndClose() throws Exception {
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        CountDownLatch openLatch = new CountDownLatch(1);
        CountDownLatch closeLatch = new CountDownLatch(1);
        AtomicReference<IConnection> connectionRef = new AtomicReference<>();
        AtomicReference<String> handlerThreadName = new AtomicReference<>();
        ExecutorService handlerExecutor = newHandlerExecutor("zero-net-tcp-handler");
        ConnectionListener listener = new ConnectionListener() {
            @Override
            public void onOpen(final IConnection connection) {
                connectionRef.set(connection);
                openLatch.countDown();
            }

            @Override
            public void onClose(final IConnection connection) {
                closeLatch.countDown();
            }
        };
        ServerFrameHandler handler = (connection, frame) -> {
            handlerThreadName.set(Thread.currentThread().getName());
            ProtocolFrame response = new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    ("tcp:" + text(frame)).getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(response));
        };
        ServerOptions options = ServerOptions.tcp("127.0.0.1", 0)
                .withIoThreads(1, 1)
                .withMaxFrameLength(1024 * 1024);
        NettyTcpServer server = new NettyTcpServer(options, frameCodec, handler, listener, handlerExecutor);

        try {
            server.start();
            ProtocolFrame response = exchangeTcp(server.boundPort(), frameCodec, frame("ping"));
            IConnection connection = connectionRef.get();

            assertTrue(server.running());
            assertTrue(openLatch.await(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(closeLatch.await(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
            assertNotNull(connection);
            assertEquals(ServerType.TCP, connection.serverType());
            assertEquals("tcp:ping", text(response));
            assertNotNull(handlerThreadName.get());
            assertFalse(handlerThreadName.get().contains("EventLoop"));
        } finally {
            server.stop();
            shutdown(handlerExecutor);
        }
    }

    /**
     * 验证 UDP 服务端可以收发协议帧。
     */
    @Test
    void udpServerShouldExchangeDatagramFrame() throws Exception {
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        AtomicReference<ServerType> connectionType = new AtomicReference<>();
        ExecutorService handlerExecutor = newHandlerExecutor("zero-net-udp-handler");
        ServerFrameHandler handler = (connection, frame) -> {
            connectionType.set(connection.serverType());
            ProtocolFrame response = new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    ("udp:" + text(frame)).getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(response));
        };
        NettyUdpServer server = new NettyUdpServer(
                ServerOptions.udp("127.0.0.1", 0).withIoThreads(1, 1),
                frameCodec,
                handler,
                new ConnectionListener() {
                },
                handlerExecutor);

        try {
            server.start();
            ProtocolFrame response = exchangeUdp(server.boundPort(), frameCodec, frame("ping"));

            assertTrue(server.running());
            assertEquals(ServerType.UDP, connectionType.get());
            assertEquals("udp:ping", text(response));
        } finally {
            server.stop();
            shutdown(handlerExecutor);
        }
    }

    /**
     * 验证 HTTP 服务端可以处理最小请求响应。
     */
    @Test
    void httpServerShouldRespond() throws Exception {
        ExecutorService handlerExecutor = newHandlerExecutor("zero-net-http-handler");
        NettyHttpServer server = new NettyHttpServer(
                ServerOptions.http("127.0.0.1", 0).withIoThreads(1, 1),
                request -> CompletableFuture.completedFuture(HttpResponse.text(
                        200,
                        "http:" + request.method() + ":" + request.uri() + ":" + request.bodyText())),
                handlerExecutor);

        try {
            server.start();
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:" + server.boundPort() + "/zero"))
                    .POST(HttpRequest.BodyPublishers.ofString("ping"))
                    .timeout(Duration.ofSeconds(3))
                    .build();
            java.net.http.HttpResponse<String> response = client.send(request, BodyHandlers.ofString());

            assertTrue(server.running());
            assertEquals(200, response.statusCode());
            assertEquals("http:POST:/zero:ping", response.body());
        } finally {
            server.stop();
            shutdown(handlerExecutor);
        }
    }

    /**
     * 验证 KCP 当前为独立边界并 fail-fast。
     */
    @Test
    void kcpServerShouldFailFastBeforeAdapterIsSelected() {
        UnsupportedKcpServer server = new UnsupportedKcpServer(ServerOptions.kcp("127.0.0.1", 0));

        ZeroException exception = assertThrows(ZeroException.class, server::start);

        assertEquals(NetErrorCode.KCP_NOT_IMPLEMENTED, exception.errorCode());
    }

    private static ExecutorService newHandlerExecutor(final String name) {
        return Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        });
    }

    private static ProtocolFrame frame(final String payload) {
        return new ProtocolFrame(62001, 1, 0, null, payload.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(final ProtocolFrame frame) {
        return new String(frame.payload(), StandardCharsets.UTF_8);
    }

    private static ProtocolFrame exchangeTcp(
            final int port,
            final ProtocolFrameCodec frameCodec,
            final ProtocolFrame request) throws Exception {
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress("127.0.0.1", port), 3000);
            socket.setSoTimeout(3000);
            DataOutputStream output = new DataOutputStream(socket.getOutputStream());
            byte[] requestBytes = frameCodec.encode(request);
            output.writeInt(requestBytes.length);
            output.write(requestBytes);
            output.flush();

            DataInputStream input = new DataInputStream(socket.getInputStream());
            int responseLength = input.readInt();
            byte[] responseBytes = input.readNBytes(responseLength);
            assertEquals(responseLength, responseBytes.length);
            return frameCodec.decode(responseBytes);
        }
    }

    private static ProtocolFrame exchangeUdp(
            final int port,
            final ProtocolFrameCodec frameCodec,
            final ProtocolFrame request) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(3000);
            byte[] requestBytes = frameCodec.encode(request);
            DatagramPacket outbound = new DatagramPacket(
                    requestBytes,
                    requestBytes.length,
                    new InetSocketAddress("127.0.0.1", port));
            socket.send(outbound);

            byte[] buffer = new byte[1024 * 1024];
            DatagramPacket inbound = new DatagramPacket(buffer, buffer.length);
            socket.receive(inbound);
            byte[] responseBytes = java.util.Arrays.copyOf(inbound.getData(), inbound.getLength());
            return frameCodec.decode(responseBytes);
        }
    }

    private static void shutdown(final ExecutorService executor) throws InterruptedException {
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
    }
}
