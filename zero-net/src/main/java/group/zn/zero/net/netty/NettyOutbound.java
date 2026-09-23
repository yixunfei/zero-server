package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.channel.Channel;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 同连接有界出站与批次刷新。准入在 EventLoop 入队之前完成，写完成/失败后释放。
 * 不取消已准入可靠消息，不创建执行器。线程安全。
 * @author zn
 */
final class NettyOutbound {
    /** 目标连接。 */
    private final Channel channel;
    /** 编码上界来源。 */
    private final ProtocolFrameCodec codec;
    /** 服务资源配置。 */
    private final ServerOptions options;
    /** 单连接精确预算。 */
    private final OutboundBudget local;
    /** 服务共享精确预算。 */
    private final OutboundBudget global;
    NettyOutbound(final Channel channel, final ProtocolFrameCodec codec, final ServerOptions options,
            final OutboundBudget global) {
        this.channel = channel;
        this.codec = codec;
        this.options = options;
        this.local = new OutboundBudget(options.tuning().maxPendingBytesPerConnection());
        this.global = global;
    }

    /** 列表在准入后冻结；整个批次统一准入，返回实际全部写完成信号。 */
    CompletionStage<Void> send(final List<ProtocolFrame> frames) {
        if (frames.isEmpty()) return CompletableFuture.completedFuture(null);
        if (!channel.isActive()) return failed(NetErrorCode.SEND_FAILED, null);
        if (!channel.isWritable() || frames.size() > options.tuning().maxPendingBytesPerConnection() / 128) {
            return failed(NetErrorCode.OUTBOUND_OVERFLOW, null);
        }
        long bytes;
        try {
            bytes = reservation(frames);
        } catch (RuntimeException failure) {
            return failed(NetErrorCode.INVALID_MESSAGE, failure);
        }
        if (!local.acquire(bytes)) return failed(NetErrorCode.OUTBOUND_OVERFLOW, null);
        if (!global.acquire(bytes)) {
            local.release(bytes);
            return failed(NetErrorCode.OUTBOUND_OVERFLOW, null);
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        try {
            List<ProtocolFrame> snapshot = List.copyOf(frames);
            if (channel.eventLoop().inEventLoop()) write(snapshot, bytes, result);
            else channel.eventLoop().execute(() -> write(snapshot, bytes, result));
        } catch (RuntimeException failure) {
            release(bytes);
            result.completeExceptionally(error(NetErrorCode.SEND_FAILED, failure));
        }
        return result;
    }

    private long reservation(final List<ProtocolFrame> frames) {
        long bytes = 0;
        for (ProtocolFrame frame : frames) {
            int bound = codec.encodedLength(frame);
            if (bound < 0) bound = options.maxFrameLength() - 4;
            if (bound < 0 || bound > options.maxFrameLength() - 4) {
                throw new IllegalArgumentException("outbound frame exceeds maxFrameLength");
            }
            // 输入快照 + 编码上界 + TCP 长度/对象开销；不承诺估算 JVM 对象精确布局。
            bytes = Math.addExact(bytes, 128L + frame.payloadLength() + frame.extensionLength() + bound + 4);
        }
        return bytes;
    }

    private void write(final List<ProtocolFrame> frames, final long bytes, final CompletableFuture<Void> result) {
        AtomicInteger remaining = new AtomicInteger(frames.size());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        for (ProtocolFrame frame : frames) {
            try {
                channel.write(frame).addListener(done -> {
                    if (!done.isSuccess()) failure.compareAndSet(null, done.cause());
                    finish(remaining, failure, bytes, result);
                });
            } catch (RuntimeException ex) {
                failure.compareAndSet(null, ex);
                finish(remaining, failure, bytes, result);
            }
        }
        try {
            channel.flush();
        } catch (RuntimeException ex) {
            failure.compareAndSet(null, ex);
            channel.close();
        }
    }

    private void finish(final AtomicInteger remaining, final AtomicReference<Throwable> failure,
            final long bytes, final CompletableFuture<Void> result) {
        if (remaining.decrementAndGet() != 0) return;
        release(bytes);
        if (failure.get() == null) result.complete(null);
        else result.completeExceptionally(error(NetErrorCode.SEND_FAILED, failure.get()));
    }

    private void release(final long bytes) { local.release(bytes); global.release(bytes); }
    long pendingBytes() { return local.used(); }
    private static CompletionStage<Void> failed(final NetErrorCode code, final Throwable cause) {
        return CompletableFuture.failedFuture(error(code, cause));
    }
    private static ZeroException error(final NetErrorCode code, final Throwable cause) {
        return ZeroException.of(code, code.message(), cause);
    }
}
