package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** B4 入站积压与绝对游标扩容边界。 @author zn */
class NettyB4BoundaryTest {
    @Test
    void absoluteWritesGrowAmortizedWithoutChangingNettyIndexes() {
        var bytes = Unpooled.buffer(1, 1024);
        try {
            var buffer = new NettyZeroBuffer(bytes, 1024);
            buffer.putByte(0, (byte) 42);
            buffer.ensureCapacity(2);
            assertTrue(buffer.capacity() > 2);
            assertEquals(42, buffer.getByte(0));
            assertEquals(0, bytes.readerIndex());
            assertEquals(0, bytes.writerIndex());
            buffer.ensureCapacity(1024);
            assertEquals(1024, buffer.capacity());
            assertThrows(IllegalArgumentException.class, () -> buffer.ensureCapacity(1025));
        } finally {
            bytes.release();
        }
    }

    @Test
    void orderedQueueIncludesActiveFrameAndRejectsOverflow() {
        var pending = new CompletableFuture<List<ProtocolFrame>>();
        var calls = new AtomicInteger();
        var observed = new AtomicReference<Throwable>();
        var options = ServerOptions.tcp("127.0.0.1", 0);
        options = options.withTuning(options.tuning().withTcpFrameOrderPreserved(true));
        var listener = new ConnectionListener() {
            @Override public void onException(final IConnection connection, final Throwable failure) {
                observed.set(failure);
            }
        };
        var channel = new EmbeddedChannel(new NettyFrameChannelHandler((connection, frame) -> {
            calls.incrementAndGet();
            return pending;
        }, listener, Runnable::run, null, options, new ZeroBinaryFrameCodec(), new OutboundBudget(1 << 20)));
        try {
            for (int i = 0; i < 1025; i++) {
                channel.writeInbound(new ProtocolFrame(1, 1, i, null, new byte[0]));
            }
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertEquals(NetErrorCode.INBOUND_OVERFLOW, ((ZeroException) observed.get()).errorCode());
            pending.complete(List.of());
            channel.runPendingTasks();
            assertEquals(1, calls.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void orderedBytesIncludeExtensionsAndWaitForResponse(final boolean pendingResponse) {
        var handler = new CompletableFuture<List<ProtocolFrame>>();
        var writes = new java.util.ArrayList<io.netty.channel.ChannelPromise>();
        var outbound = new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void write(final io.netty.channel.ChannelHandlerContext context, final Object message,
                    final io.netty.channel.ChannelPromise promise) {
                writes.add(promise);
            }
        };
        var errors = new AtomicInteger();
        var options = ServerOptions.tcp("localhost", 0);
        options = options.withTuning(options.tuning().withTcpFrameOrderPreserved(true).withOrderedInboundLimits(5, 258));
        var channel = new EmbeddedChannel(outbound, new NettyFrameChannelHandler((connection, frame) -> handler,
                new ConnectionListener() {
                    @Override public void onException(final IConnection connection, final Throwable cause) {
                        assertEquals(NetErrorCode.INBOUND_OVERFLOW, ((ZeroException) cause).errorCode());
                        errors.incrementAndGet();
                    }
                }, Runnable::run, null, options, new ZeroBinaryFrameCodec(), new OutboundBudget(1024)));
        try {
            var frame = new ProtocolFrame(1, 1, 0, new byte[]{1}, new byte[]{2});
            channel.writeInbound(frame);
            if (pendingResponse) handler.complete(List.of(frame));
            channel.writeInbound(frame);
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            handler.complete(List.of());
            writes.forEach(promise -> promise.trySuccess());
            channel.runPendingTasks();
            assertEquals(1, errors.get());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test
    void closeFailureHasConnectionSpecificCode() {
        var channel = new EmbeddedChannel(new io.netty.channel.ChannelOutboundHandlerAdapter() {
            @Override public void close(final io.netty.channel.ChannelHandlerContext context,
                    final io.netty.channel.ChannelPromise promise) {
                promise.setFailure(new IllegalStateException("close failed"));
            }
        });
        try {
            var connection = new NettyConnection("test", channel);
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> connection.close().toCompletableFuture().join());
            assertEquals(NetErrorCode.CONNECTION_CLOSE_FAILED, ((ZeroException) failure.getCause()).errorCode());
        } finally {
            channel.pipeline().removeFirst();
            channel.finishAndReleaseAll();
        }
    }
}
