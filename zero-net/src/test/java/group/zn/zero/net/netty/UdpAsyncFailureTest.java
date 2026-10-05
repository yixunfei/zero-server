package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.DatagramPacket;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 无 socket/计时等待地验证 UDP 异步 handler、响应校验与写失败均可观察。 @author zn */
class UdpAsyncFailureTest {
    /** 回调晚于 handler 返回；不得将错误保留在无人读取的完成阶段。 */
    @ParameterizedTest @ValueSource(strings = {"handler", "null-response", "write"})
    void observesEveryAsynchronousFailure(final String mode) throws Exception {
        var result = new CompletableFuture<List<ProtocolFrame>>();
        var observation = new CompletableFuture<Throwable>();
        var observedConnection = new CompletableFuture<IConnection>();
        var codec = new ZeroBinaryFrameCodec();
        var failure = new IllegalStateException("injected failure");
        var server = new NettyUdpServer(ServerOptions.udp("127.0.0.1", 0), codec,
                (connection, frame) -> result, new ConnectionListener() {
                    @Override public void onException(final IConnection connection, final Throwable cause) {
                        observedConnection.complete(connection);
                        observation.complete(cause);
                    }
                }, Runnable::run);
        var constructor = Class.forName(NettyUdpServer.class.getName() + "$UdpFrameHandler")
                .getDeclaredConstructor(NettyUdpServer.class);
        constructor.setAccessible(true);
        var inbound = (ChannelHandler) constructor.newInstance(server);
        var outbound = new ChannelOutboundHandlerAdapter() {
            @Override public void write(final ChannelHandlerContext context, final Object message,
                    final ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                promise.setFailure(failure);
            }
        };
        var channel = new EmbeddedChannel(outbound, inbound);
        try {
            var frame = new ProtocolFrame(1, 1, 0, null, new byte[]{1});
            channel.writeInbound(new DatagramPacket(Unpooled.wrappedBuffer(codec.encode(frame)),
                    new InetSocketAddress("127.0.0.1", 9000), new InetSocketAddress("127.0.0.1", 9001)));
            switch (mode) {
                case "handler" -> result.completeExceptionally(failure);
                case "null-response" -> result.complete(null);
                default -> result.complete(List.of(frame));
            }
            channel.runPendingTasks();
            assertNotNull(observation.getNow(null), "asynchronous failures must reach the listener");
            assertNotNull(observedConnection.get(2, TimeUnit.SECONDS));
            var error = (ZeroException) observation.get(2, TimeUnit.SECONDS);
            assertEquals((mode.equals("write") ? NetErrorCode.SEND_FAILED : NetErrorCode.HANDLER_FAILED).code(),
                    error.errorCode().code());
            if (!mode.equals("null-response")) assertSame(failure, error.getCause());
        } finally { channel.finishAndReleaseAll(); }
    }
}
