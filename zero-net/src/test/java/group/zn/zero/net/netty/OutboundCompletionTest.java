package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelPromise;
import io.netty.channel.DefaultChannelPromise;
import io.netty.channel.EventLoop;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import org.junit.jupiter.api.Test;

/** 确定性注入 promise/flush/投递时序，验证可靠写的预算所有权。 @author zn */
class OutboundCompletionTest {
    /** 不可变测试帧。 */
    private static final ProtocolFrame FRAME = new ProtocolFrame(1, 1, 0, null, new byte[64]);

    @Test void synchronousPromisesMustWaitForFlushAndReportItsFailure() {
        for (int count : new int[]{1, 2, 8}) {
            try (Fixture fixture = new Fixture()) {
                fixture.flushFailure = true;
                var result = fixture.connection.sendFrames(java.util.Collections.nCopies(count, FRAME)).toCompletableFuture();
                assertFailed(result, NetErrorCode.SEND_FAILED);
                assertTrue(fixture.reservedAtFlush > 0);
                assertEquals(1, fixture.closes);
                fixture.assertReleased();
            }
        }
    }

    @Test void flushFailureCannotReleaseOutstandingWrites() {
        try (Fixture fixture = new Fixture()) {
            fixture.immediate = false;
            fixture.flushFailure = true;
            var result = fixture.connection.sendFrames(List.of(FRAME, FRAME)).toCompletableFuture();
            assertFalse(result.isDone());
            long reserved = fixture.budget.used();
            fixture.promises.getFirst().setSuccess();
            assertEquals(reserved, fixture.budget.used());
            fixture.promises.getLast().setFailure(new IllegalStateException("connection closed"));
            assertFailed(result, NetErrorCode.SEND_FAILED);
            fixture.assertReleased();
        }
    }

    @Test void writeThrowAndAsyncFailureWaitForOtherWrites() {
        try (Fixture fixture = new Fixture()) {
            fixture.immediate = false;
            fixture.throwWrite = 2;
            var result = fixture.connection.sendFrames(List.of(FRAME, FRAME, FRAME)).toCompletableFuture();
            assertEquals(2, fixture.promises.size());
            fixture.promises.getFirst().setFailure(new IllegalStateException("write failed"));
            assertFalse(result.isDone());
            assertTrue(fixture.budget.used() > 0);
            fixture.promises.getLast().setSuccess();
            assertFailed(result, NetErrorCode.SEND_FAILED);
            fixture.assertReleased();
        }
    }

    @Test void cancellationAndRepeatedPromiseCompletionDoNotReleaseEarlyOrTwice() {
        try (Fixture fixture = new Fixture()) {
            fixture.immediate = false;
            var result = fixture.connection.send(FRAME).toCompletableFuture();
            assertTrue(result.cancel(false));
            assertTrue(fixture.budget.used() > 0);
            assertTrue(fixture.promises.getFirst().trySuccess());
            assertFalse(fixture.promises.getFirst().trySuccess());
            assertTrue(result.isCancelled());
            fixture.assertReleased();
        }
    }

    @Test void eventLoopRejectionAndDisconnectedQueueReleaseExactlyOnce() {
        try (Fixture fixture = new Fixture()) {
            fixture.outside = true;
            fixture.reject = true;
            assertFailed(fixture.connection.send(FRAME).toCompletableFuture(), NetErrorCode.SEND_FAILED);
            fixture.assertReleased();
            fixture.reject = false;
            var result = fixture.connection.sendFrames(new ArrayList<>(List.of(FRAME, FRAME))).toCompletableFuture();
            assertTrue(fixture.budget.used() > 0);
            fixture.active = false;
            fixture.queued.run();
            assertFailed(result, NetErrorCode.SEND_FAILED);
            fixture.assertReleased();
        }
    }

    @Test void reentrantCompletionSeesReleasedBudgetAndMutableBatchIsFrozen() {
        try (Fixture fixture = new Fixture()) {
            fixture.outside = true;
            var frames = new ArrayList<>(List.of(FRAME, FRAME));
            var first = fixture.connection.sendFrames(frames).toCompletableFuture();
            frames.clear();
            var result = first.thenCompose(ignored -> {
                fixture.assertReleased();
                fixture.outside = false;
                return fixture.connection.send(FRAME);
            });
            fixture.queued.run();
            result.join();
            assertEquals(3, fixture.writes);
            fixture.assertReleased();
        }
    }

    private static void assertFailed(final CompletableFuture<Void> result, final NetErrorCode code) {
        var failure = assertThrows(CompletionException.class, result::join);
        assertEquals(code, ((ZeroException) failure.getCause()).errorCode());
    }

    /** 只模拟 Channel 契约边界；真实 Netty promise，允许可控同步/延后完成。 @author zn */
    private static final class Fixture implements AutoCloseable {
        /** promise 关联的线程私有通道。 */
        private final EmbeddedChannel embedded = new EmbeddedChannel();
        /** 服务共享额度。 */
        private final OutboundBudget budget = new OutboundBudget(1024 * 1024);
        /** 已成功提交的写。 */
        private final List<ChannelPromise> promises = new ArrayList<>();
        /** 被测连接。 */
        private final NettyConnection connection;
        /** 同步完成写。 */
        private boolean immediate = true;
        /** 当前连接状态。 */
        private boolean active = true;
        /** 模拟外部生产者。 */
        private boolean outside;
        /** 模拟执行器拒绝。 */
        private boolean reject;
        /** 模拟 flush 抛错。 */
        private boolean flushFailure;
        /** 第几次 write 同步抛错。 */
        private int throwWrite;
        /** 写调用数。 */
        private int writes;
        /** 关闭调用数。 */
        private int closes;
        /** flush 尚未返回时的资源占用。 */
        private long reservedAtFlush;
        /** 唯一待投递任务。 */
        private Runnable queued;

        private Fixture() {
            EventLoop loop = (EventLoop) Proxy.newProxyInstance(EventLoop.class.getClassLoader(),
                    new Class<?>[]{EventLoop.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("inEventLoop")) return !outside;
                        if (method.getName().equals("execute")) {
                            if (reject) throw new RejectedExecutionException("test rejection");
                            queued = (Runnable) arguments[0];
                            return null;
                        }
                        throw new UnsupportedOperationException(method.getName());
                    });
            Channel channel = (Channel) Proxy.newProxyInstance(Channel.class.getClassLoader(),
                    new Class<?>[]{Channel.class}, (proxy, method, arguments) -> switch (method.getName()) {
                        case "isActive" -> active;
                        case "isWritable" -> true;
                        case "eventLoop" -> loop;
                        case "write" -> write();
                        case "flush" -> {
                            reservedAtFlush = budget.used();
                            if (flushFailure) throw new IllegalStateException("flush failed");
                            yield proxy;
                        }
                        case "close" -> { closes++; yield embedded.newSucceededFuture(); }
                        default -> throw new UnsupportedOperationException(method.getName());
                    });
            connection = new NettyConnection("test", channel, new ZeroBinaryFrameCodec(),
                    ServerOptions.tcp("localhost", 0), budget);
        }

        private ChannelPromise write() {
            if (++writes == throwWrite) throw new IllegalStateException("write threw");
            ChannelPromise promise = new DefaultChannelPromise(embedded, ImmediateEventExecutor.INSTANCE);
            promises.add(promise);
            if (!active) promise.setFailure(new IllegalStateException("closed"));
            else if (immediate) promise.setSuccess();
            return promise;
        }

        private void assertReleased() {
            assertEquals(0, budget.used());
            assertEquals(0, connection.pendingOutboundBytes());
        }

        /** 归还测试通道，无外部执行器。 */
        @Override public void close() { embedded.finishAndReleaseAll(); }
    }
}
