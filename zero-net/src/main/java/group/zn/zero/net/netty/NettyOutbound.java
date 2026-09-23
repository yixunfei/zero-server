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
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;

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
        if (frames.size() == 1) return send(frames.getFirst());
        return send(null, frames);
    }

    /** 单帧不经过批次包装，资源口径与批次一致。 */
    CompletionStage<Void> send(final ProtocolFrame frame) {
        return send(frame, null);
    }

    private CompletionStage<Void> send(final ProtocolFrame frame, final List<ProtocolFrame> frames) {
        if (!channel.isActive()) return failed(NetErrorCode.SEND_FAILED, null);
        int count = frames == null ? 1 : frames.size();
        if (!channel.isWritable() || count > options.tuning().maxPendingBytesPerConnection() / 128) {
            return failed(NetErrorCode.OUTBOUND_OVERFLOW, null);
        }
        long bytes;
        try {
            bytes = frames == null ? reservation(frame) : reservation(frames);
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
            List<ProtocolFrame> snapshot = frames == null ? null : List.copyOf(frames);
            if (channel.eventLoop().inEventLoop()) write(frame, snapshot, bytes, result);
            else channel.eventLoop().execute(() -> write(frame, snapshot, bytes, result));
        } catch (RuntimeException failure) {
            release(bytes);
            result.completeExceptionally(error(NetErrorCode.SEND_FAILED, failure));
        }
        return result;
    }

    private long reservation(final List<ProtocolFrame> frames) {
        long bytes = 0;
        for (ProtocolFrame frame : frames) {
            bytes = Math.addExact(bytes, reservation(frame));
        }
        return bytes;
    }

    private long reservation(final ProtocolFrame frame) {
        int bound = codec.encodedLength(frame);
        if (bound < 0) bound = options.maxFrameLength() - 4;
        if (bound < 0 || bound > options.maxFrameLength() - 4) {
            throw new IllegalArgumentException("outbound frame exceeds maxFrameLength");
        }
        // 输入快照 + 编码上界 + TCP 长度/对象开销；不承诺估算 JVM 对象精确布局。
        return 128L + frame.payloadLength() + frame.extensionLength() + bound + 4;
    }

    private void write(final ProtocolFrame frame, final List<ProtocolFrame> frames,
            final long bytes, final CompletableFuture<Void> result) {
        WriteCompletion completion = new WriteCompletion(frames == null ? 1 : frames.size(), bytes, result);
        if (frames == null) writeFrame(frame, completion);
        else for (ProtocolFrame current : frames) writeFrame(current, completion);
        Throwable failure = null;
        try {
            channel.flush();
        } catch (RuntimeException ex) {
            failure = ex;
            try { channel.close(); }
            catch (RuntimeException closeFailure) { ex.addSuppressed(closeFailure); }
        } finally {
            // 即使所有 promise 同步完成，也必须等 flush 返回，才能报告成功和归还预算。
            completion.finish(failure);
        }
    }

    private void writeFrame(final ProtocolFrame frame, final WriteCompletion completion) {
        try { channel.write(frame).addListener(completion); }
        catch (RuntimeException failure) { completion.finish(failure); }
    }

    private void release(final long bytes) { local.release(bytes); global.release(bytes); }
    long pendingBytes() { return local.used(); }
    private static CompletionStage<Void> failed(final NetErrorCode code, final Throwable cause) {
        return CompletableFuture.failedFuture(error(code, cause));
    }
    private static ZeroException error(final NetErrorCode code, final Throwable cause) {
        return ZeroException.of(code, code.message(), cause);
    }

    /** 每次发送独占的写/flush 屏障；不以调用者 Future 的取消状态决定资源释放。 @author zn */
    private final class WriteCompletion implements ChannelFutureListener {
        /** 所有写 promise 加一个 flush 屏障；仅在本对象锁内修改。 */
        private int remaining;
        /** 首个失败；仅在本对象锁内访问。 */
        private Throwable failure;
        /** 本次资源许可。 */
        private final long bytes;
        /** 调用者独立完成信号。 */
        private final CompletableFuture<Void> result;

        private WriteCompletion(final int writes, final long bytes, final CompletableFuture<Void> result) {
            this.remaining = writes + 1;
            this.bytes = bytes;
            this.result = result;
        }

        /** Netty 保证每个 promise 的本监听器仅通知一次；跨线程完成安全。 */
        @Override public void operationComplete(final ChannelFuture future) {
            finish(future.isSuccess() ? null : future.cause());
        }

        private void finish(final Throwable cause) {
            Throwable terminalFailure;
            synchronized (this) {
                if (failure == null) failure = cause;
                if (--remaining != 0) return;
                terminalFailure = failure;
            }
            release(bytes);
            if (terminalFailure == null) result.complete(null);
            else result.completeExceptionally(error(NetErrorCode.SEND_FAILED, terminalFailure));
        }
    }
}
