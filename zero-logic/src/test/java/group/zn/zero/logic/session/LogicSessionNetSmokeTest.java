package group.zn.zero.logic.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.logic.LocalLogicExample;
import group.zn.zero.logic.LogicFlowResult;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyTcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * 业务逻辑会话与网络链路 smoke test。
 *
 * @author zn
 */
class LogicSessionNetSmokeTest {

    /**
     * 验证业务逻辑示例可以通过 TCP server 与业务 session 串联。
     */
    @Test
    void logicSessionShouldWorkWithTcpServer() throws Exception {
        ProtocolFrameCodec frameCodec = new ZeroBinaryFrameCodec();
        LocalLogicExample logicExample = new LocalLogicExample();
        LogicSessionManager sessionManager = new LogicSessionManager();
        CountDownLatch closeLatch = new CountDownLatch(1);
        ExecutorService handlerExecutor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "zero-logic-net-smoke");
            thread.setDaemon(true);
            return thread;
        });
        ConnectionListener listener = new ConnectionListener() {
            @Override
            public void onClose(final IConnection connection) {
                sessionManager.close(connection);
                closeLatch.countDown();
            }
        };
        ServerFrameHandler handler = (connection, frame) -> {
            LogicSession session = sessionManager.open(connection);
            session.attributes().put(StandardLogicSessionAttributes.CHANNEL_CODE, "tcp-smoke");
            sessionManager.markRequest(session);
            LogicFlowResult flow = logicExample.runPlayerQuery("1001", "event-net-1", "trace-net-1");
            ProtocolFrame response = new ProtocolFrame(
                    frame.protocolId(),
                    frame.protocolVersion(),
                    frame.flags(),
                    frame.extension(),
                    String.join("|", flow.steps()).getBytes(StandardCharsets.UTF_8));
            return CompletableFuture.completedFuture(List.of(response));
        };
        NettyTcpServer server = new NettyTcpServer(
                ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1),
                frameCodec,
                handler,
                listener,
                handlerExecutor);

        try {
            server.start();
            ProtocolFrame response = exchange(server.boundPort(), frameCodec, new ProtocolFrame(
                    logicExample.protocolDefinition().id(),
                    logicExample.protocolDefinition().version(),
                    0,
                    null,
                    "ping".getBytes(StandardCharsets.UTF_8)));

            assertTrue(new String(response.payload(), StandardCharsets.UTF_8)
                    .contains("actor:ZeroLogicQueryPlayerProtocol:1001"));
            assertTrue(closeLatch.await(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
            assertTrue(sessionManager.sessions().isEmpty());
        } finally {
            server.stop();
            handlerExecutor.shutdownNow();
            assertTrue(handlerExecutor.awaitTermination(Duration.ofSeconds(3).toMillis(), TimeUnit.MILLISECONDS));
        }
    }

    private static ProtocolFrame exchange(
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
}
