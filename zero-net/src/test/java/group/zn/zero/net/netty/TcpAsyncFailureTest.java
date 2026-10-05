package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** TCP 异步异常必须经过本连接的异常处理与关闭，不能跳过当前 pipeline handler。 @author zn */
class TcpAsyncFailureTest {
    /** handler 和 codec 的失败均通知一次；观察器再失败也必须完成关闭，禁止递归通知。 */
    @ParameterizedTest @ValueSource(strings = {"handler", "codec", "observer"})
    void asynchronousFailuresNotifyListenerAndClose(final String mode) throws Exception {
        var result = new CompletableFuture<List<ProtocolFrame>>();
        var failure = new AssertionError("asynchronous failure");
        var observed = new CompletableFuture<Throwable>();
        var exceptions = new AtomicInteger();
        var closed = new AtomicInteger();
        var listener = new ConnectionListener() {
            @Override public void onException(final IConnection connection, final Throwable cause) {
                exceptions.incrementAndGet();
                observed.complete(cause);
                if (mode.equals("observer")) throw new AssertionError("observer failed");
            }
            @Override public void onClose(final IConnection connection) { closed.incrementAndGet(); }
        };
        ProtocolFrameCodec codec = mode.equals("codec") ? new ProtocolFrameCodec() {
            @Override public byte[] encode(final ProtocolFrame frame) { return new byte[0]; }
            @Override public ProtocolFrame decode(final byte[] bytes) { throw new UnsupportedOperationException(); }
            @Override public int encodedLength(final ProtocolFrame frame) { throw failure; }
        } : new ZeroBinaryFrameCodec();
        var channel = new EmbeddedChannel(new NettyFrameChannelHandler((connection, frame) -> result,
                listener, Runnable::run, null, ServerOptions.tcp("127.0.0.1", 0), codec,
                new OutboundBudget(1024 * 1024)));
        try {
            var frame = new ProtocolFrame(1, 1, 0, null, new byte[]{1});
            channel.writeInbound(frame);
            if (mode.equals("codec")) result.complete(List.of(frame));
            else result.completeExceptionally(failure);
            try { channel.runPendingTasks(); }
            catch (AssertionError observerFailure) {
                if (!mode.equals("observer")) throw observerFailure;
                assertEquals("observer failed", observerFailure.getMessage());
            }
            assertFalse(channel.isActive());
            assertEquals(1, exceptions.get());
            assertEquals(1, closed.get());
            var error = (ZeroException) observed.get(2, TimeUnit.SECONDS);
            assertEquals(NetErrorCode.HANDLER_FAILED, error.errorCode());
            assertSame(failure, error.getCause());
        } finally {
            // EventLoop 可以记录或直接传播观察器 Error；两种情况下本地清理都必须已完成。
            if (mode.equals("observer")) {
                try { channel.checkException(); }
                catch (AssertionError observerFailure) {
                    assertEquals("observer failed", observerFailure.getMessage());
                }
            }
            channel.finishAndReleaseAll();
        }
    }
}
