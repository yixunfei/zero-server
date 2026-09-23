package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.NetworkTransport;
import group.zn.zero.net.NetworkTuning;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 使用真实不读数据的 TCP 对端验证待写资源上限与断连后的释放。 @author zn */
class SlowConsumerBudgetTest {
    @Test void stalledReaderCannotAccumulateUnboundedWrites() throws Exception {
        long limit = 4L * 1024 * 1024;
        var options = ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1).withMaxFrameLength(1024 * 1024)
                .withTuning(new NetworkTuning(NetworkTransport.NIO, 128, 8192, 16384, limit, limit, 0));
        var opened = new CountDownLatch(1);
        var connection = new AtomicReference<NettyConnection>();
        var listener = new ConnectionListener() {
            @Override public void onOpen(final IConnection value) {
                connection.set((NettyConnection) value);
                opened.countDown();
            }
        };
        var server = new NettyTcpServer(options, new ZeroBinaryFrameCodec(),
                (conn, frame) -> CompletableFuture.completedFuture(List.of()), listener, Runnable::run);
        List<CompletableFuture<Void>> writes = new ArrayList<>();
        try {
            server.start();
            try (Socket slow = new Socket()) {
                slow.setReceiveBufferSize(1024);
                slow.connect(new InetSocketAddress("127.0.0.1", server.boundPort()));
                assertTrue(opened.await(5, TimeUnit.SECONDS));
                ProtocolFrame frame = new ProtocolFrame(1, 1, 0, null, new byte[65536]);
                for (int i = 0; i < 1000; i++) {
                    writes.add(connection.get().sendFrame(frame).toCompletableFuture());
                    assertTrue(connection.get().pendingOutboundBytes() <= limit);
                }
                assertTrue(writes.stream().anyMatch(CompletableFuture::isCompletedExceptionally));
            }
            connection.get().close().toCompletableFuture().get(5, TimeUnit.SECONDS);
            CompletableFuture.allOf(writes.stream().map(write -> write.handle((value, failure) -> null))
                    .toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            assertEquals(0, connection.get().pendingOutboundBytes());
        } finally { server.stop(); }
    }
}
