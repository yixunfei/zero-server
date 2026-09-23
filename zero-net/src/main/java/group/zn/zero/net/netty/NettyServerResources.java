package group.zn.zero.net.netty;

import group.zn.zero.net.ServerOptions;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.group.ChannelGroup;
import io.netty.channel.group.DefaultChannelGroup;
import io.netty.util.concurrent.ImmediateEventExecutor;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** 每次 server.start 独占的 channel 生命周期；借用组绝不由 server 关闭。 @author zn */
final class NettyServerResources implements AutoCloseable {
    /** 自有或借用的 IO 资源。 */
    private final NettyIoResources io;
    /** 是否由该 server 拥有 IO 组。 */
    private final boolean owned;
    /** 所有已接入连接；关闭后的迟到 accept 立即关闭，不使用全局辅助线程。 */
    private final ChannelGroup children = new DefaultChannelGroup(ImmediateEventExecutor.INSTANCE, true);
    /** 监听 channel；bind 等待之前保存，确保中断/失败时可回收。 */
    private Channel listener;
    /** 幂等关闭标志。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 当前 server 的连接关闭信号。 */
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    NettyServerResources(final ServerOptions options, final NettyIoResources borrowed) {
        owned = borrowed == null;
        io = owned ? NettyIoResources.open(options) : borrowed;
        io.validate(options);
    }

    NettyIoResources io() { return io; }
    void track(final Channel channel) { children.add(channel); }
    Channel bind(final ChannelFuture future) throws InterruptedException {
        listener = future.channel();
        // Netty 对已完成的 future 不检查中断；显式检查，避免快速 bind 随时序忽略取消。
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedException("server bind interrupted");
        }
        future.sync();
        return listener;
    }

    /** 先停止接入，再终结所属连接和写 promise，最后归还自有组；IO 线程不自等待。 */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            if (listener == null) closeChildren(null);
            else listener.close().addListener(done -> closeChildren(done.isSuccess() ? null : done.cause()));
        }
        if (!io.inEventLoop()) {
            NettyIoResources.await(drained);
            if (owned) io.close();
        }
    }

    private void closeChildren(final Throwable listenerFailure) {
        children.close().addListener(done -> {
            Throwable failure = listenerFailure == null ? done.cause() : listenerFailure;
            try {
                if (owned) io.close();
                if (failure == null) drained.complete(null);
                else drained.completeExceptionally(failure);
            } catch (RuntimeException closeFailure) {
                if (failure != null) closeFailure.addSuppressed(failure);
                drained.completeExceptionally(closeFailure);
            }
        });
    }
}
