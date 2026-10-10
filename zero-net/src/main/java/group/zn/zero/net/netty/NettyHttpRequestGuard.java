package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.error.NetErrorCode;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * HTTP 每连接请求预算与有界串行派发。所有状态只在 EventLoop 访问。
 * 原始输入绝对期限 → 聚合完成重置期限 → 串行派发 → 响应写完后派发下一请求。
 * @author zn
 */
final class NettyHttpRequestGuard extends ChannelDuplexHandler {
    /** 等待请求数量上限。 */
    private static final int MAX_PENDING = 16;
    /** 等待请求内容预算。 */
    private final int maxPendingBytes;
    /** 等待队列拥有请求引用，关闭时必须释放。 */
    private final ArrayDeque<FullHttpRequest> pending = new ArrayDeque<>();
    /** 输入期限处理器，位于 codec 之前以覆盖慢速头。 */
    private final InputDeadline inputDeadline = new InputDeadline();
    /** 队列总字节数。 */
    private long pendingBytes;
    /** 是否有一个请求正在处理。 */
    private boolean busy;
    /** 当前请求响应期限。 */
    private ScheduledFuture<?> responseDeadline;
    /** 完整请求与业务响应的绝对期限。 */
    private final long timeoutNanos;

    NettyHttpRequestGuard(final int maxPendingBytes) {
        this(maxPendingBytes, group.zn.zero.net.ServerOptions.DEFAULT_HTTP_REQUEST_TIMEOUT);
    }

    NettyHttpRequestGuard(final int maxPendingBytes, final java.time.Duration timeout) {
        this.maxPendingBytes = maxPendingBytes;
        java.time.Duration checked = java.util.Objects.requireNonNull(timeout, "timeout");
        if (checked.isZero() || checked.isNegative()) throw new IllegalArgumentException("timeout must be positive");
        timeoutNanos = checked.toNanos();
    }

    /** 返回同连接的原始输入期限处理器，不可复用到其他连接。 */
    ChannelInboundHandlerAdapter inputDeadline() { return inputDeadline; }

    @Override public void channelRead(final ChannelHandlerContext context, final Object message) {
        if (!(message instanceof FullHttpRequest request)) {
            context.fireChannelRead(message);
            return;
        }
        inputDeadline.reset(context);
        if (!busy) {
            dispatch(context, request);
        } else if (pending.size() >= MAX_PENDING
                || pendingBytes + request.content().readableBytes() > maxPendingBytes) {
            ReferenceCountUtil.release(request);
            fail(context, "HTTP pending request capacity exceeded");
        } else {
            pending.addLast(request);
            pendingBytes += request.content().readableBytes();
        }
    }

    private void dispatch(final ChannelHandlerContext context, final FullHttpRequest request) {
        busy = true;
        responseDeadline = context.executor().schedule(
                () -> fail(context, "HTTP response deadline exceeded"), timeoutNanos, TimeUnit.NANOSECONDS);
        context.fireChannelRead(request);
    }

    @Override public void write(final ChannelHandlerContext context, final Object message, final ChannelPromise promise) {
        if (message instanceof FullHttpResponse response && response.status().code() >= 200) {
            promise.addListener(completed -> {
                if (responseDeadline != null) responseDeadline.cancel(false);
                busy = false;
                if (!completed.isSuccess()) context.close();
                else context.executor().execute(() -> next(context));
            });
        }
        context.write(message, promise);
    }

    private void next(final ChannelHandlerContext context) {
        if (!context.channel().isActive() || busy || pending.isEmpty()) return;
        FullHttpRequest request = pending.removeFirst();
        pendingBytes -= request.content().readableBytes();
        dispatch(context, request);
    }

    @Override public void channelInactive(final ChannelHandlerContext context) {
        if (responseDeadline != null) responseDeadline.cancel(false);
        while (!pending.isEmpty()) ReferenceCountUtil.release(pending.removeFirst());
        pendingBytes = 0;
        context.fireChannelInactive();
    }

    private void fail(final ChannelHandlerContext context, final String message) {
        try { context.fireExceptionCaught(ZeroException.of(NetErrorCode.HANDLER_FAILED, message, null)); }
        finally { context.close(); }
    }

    /** 完整请求之间的绝对期限同时覆盖空闲、慢速头和慢速 body。 */
    private final class InputDeadline extends ChannelInboundHandlerAdapter {
        /** 当前输入期限任务。 */
        private ScheduledFuture<?> deadline;
        @Override public void channelActive(final ChannelHandlerContext context) {
            reset(context);
            context.fireChannelActive();
        }
        private void reset(final ChannelHandlerContext context) {
            if (deadline != null) deadline.cancel(false);
            deadline = context.executor().schedule(
                    () -> fail(context, "HTTP complete request deadline exceeded"), timeoutNanos, TimeUnit.NANOSECONDS);
        }
        @Override public void channelInactive(final ChannelHandlerContext context) {
            if (deadline != null) deadline.cancel(false);
            context.fireChannelInactive();
        }
    }
}
