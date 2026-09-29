package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.NetworkTransport;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import io.netty.channel.EventLoopGroup;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Netty IO 组的唯一拥有者。组合根可显式共享；服务器只借用并拥有各自 channel。
 * 不向业务暴露执行器，不与 Actor/remoteIo 混用。线程安全，关闭幂等。
 * @author zn
 */
public final class NettyIoResources implements AutoCloseable {
    /** 有界关闭期限；不等待默认 quiet period。 */
    static final int CLOSE_SECONDS = 5;
    /** 实际传输类型，AUTO 解析为 NIO。 */
    private final NetworkTransport transport;
    /** 接入线程组；纯 UDP 资源不创建 boss。 */
    private final EventLoopGroup boss;
    /** IO 工作线程组。 */
    private final EventLoopGroup workers;
    /** 已发起关闭。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 所有组实际结束的信号，不向调用者暴露可修改原件。 */
    private final CompletableFuture<Void> termination;

    private NettyIoResources(final ServerOptions options) {
        transport = effective(options.tuning().transport());
        EventLoopGroup acceptors = datagram(options) ? null
                : NettyTransportFactory.eventLoops(transport, options.bossThreads());
        try {
            workers = NettyTransportFactory.eventLoops(transport, options.workerThreads());
        } catch (RuntimeException | Error failure) {
            if (acceptors != null) acceptors.shutdownGracefully(0, CLOSE_SECONDS, TimeUnit.SECONDS);
            throw failure;
        }
        boss = acceptors;
        termination = boss == null ? terminated(workers) : CompletableFuture.allOf(terminated(boss), terminated(workers));
    }

    /**
     * 创建由调用方拥有的资源；复用既有 IO 线程数配置，尚不监听端口。
     * @param options 传输和线程预算；不可为空。
     * @return 新资源，不可为空；应立即登记到 runtime 或使用 try-with-resources。
     * @throws NullPointerException 配置为空。
     * @throws ZeroException 传输不可用或资源创建失败。
     */
    public static NettyIoResources open(final ServerOptions options) {
        Objects.requireNonNull(options, "options");
        try { return new NettyIoResources(options); }
        catch (RuntimeException failure) {
            throw ZeroException.of(NetErrorCode.START_FAILED, "create Netty IO resources failed", failure);
        }
    }

    /** @return 不可变诊断快照，包含有效线程预算；线程安全，不改变资源。 */
    public Snapshot snapshot() {
        return new Snapshot(transport, boss == null ? 0 : count(boss), count(workers), closed.get(), termination.isDone());
    }

    /** @return 所有组实际结束的独立完成信号；调用者取消不会取消组关闭，线程安全。 */
    public CompletionStage<Void> termination() { return termination.copy(); }

    /**
     * 关闭所属组；外部线程最多等待 5 秒，在本资源 IO 线程只发起关闭，避免自等待。
     * 已关联的 server 应先停止接入和关闭连接；重复调用幂等，保留线程中断状态。
     * @throws ZeroException 外部线程等待超时或组终止失败。
     */
    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            if (boss != null) boss.shutdownGracefully(0, CLOSE_SECONDS, TimeUnit.SECONDS);
            workers.shutdownGracefully(0, CLOSE_SECONDS, TimeUnit.SECONDS);
        }
        if (!inEventLoop()) await(termination);
    }

    void validate(final ServerOptions options) {
        if (closed.get() || transport != effective(options.tuning().transport())
                || (!datagram(options) && boss == null)) {
            throw new IllegalStateException("Netty IO resources are closed or incompatible with server transport");
        }
    }

    EventLoopGroup boss() { return boss; }

    /**
     * 为 UDP/KCP Adapter 绑定一个独立 socket；不转移 IO 组所有权，线程安全。
     * @param options 数据报设置，传输必须与资源匹配。
     * @param handler 新建的、未共享的入站处理器。
     * @return bind 信号；调用者须保存并在失败和停止时关闭其 channel。
     * @throws IllegalStateException 资源关闭或传输不匹配。
     * @throws IllegalArgumentException 设置不是数据报服务器。
     */
    public io.netty.channel.ChannelFuture bindDatagram(final ServerOptions options,
            final io.netty.channel.ChannelHandler handler) {
        validate(options);
        if (!datagram(options)) throw new IllegalArgumentException("datagram server required");
        return new io.netty.bootstrap.Bootstrap().group(workers)
                .channel(NettyTransportFactory.datagramChannel(options.tuning().transport()))
                .option(io.netty.channel.ChannelOption.RCVBUF_ALLOCATOR,
                        new io.netty.channel.FixedRecvByteBufAllocator(65535))
                .option(io.netty.channel.ChannelOption.WRITE_BUFFER_WATER_MARK,
                        new io.netty.channel.WriteBufferWaterMark(options.tuning().writeLowWaterMark(),
                                options.tuning().writeHighWaterMark()))
                .handler(Objects.requireNonNull(handler, "handler")).bind(options.host(), options.port());
    }

    private static boolean datagram(final ServerOptions options) {
        return options.serverType() == ServerType.UDP || options.serverType() == ServerType.KCP;
    }
    EventLoopGroup workers() { return workers; }
    boolean inEventLoop() { return inGroup(workers) || boss != null && inGroup(boss); }

    private static boolean inGroup(final EventLoopGroup group) {
        for (var executor : group) if (executor.inEventLoop()) return true;
        return false;
    }

    private static int count(final EventLoopGroup group) {
        int result = 0;
        for (var ignored : group) result++;
        return result;
    }

    private static NetworkTransport effective(final NetworkTransport transport) {
        return transport == NetworkTransport.AUTO ? NetworkTransport.NIO : transport;
    }

    private static CompletableFuture<Void> terminated(final EventLoopGroup group) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        group.terminationFuture().addListener(done -> {
            if (done.isSuccess()) result.complete(null);
            else result.completeExceptionally(done.cause());
        });
        return result;
    }

    static void await(final CompletableFuture<Void> result) {
        boolean interrupted = Thread.interrupted();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(CLOSE_SECONDS);
        try {
            while (true) {
                try {
                    result.get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                    return;
                } catch (InterruptedException interruption) {
                    interrupted = true;
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
                    throw ZeroException.of(NetErrorCode.STOP_FAILED, "Netty resources did not terminate", failure);
                }
            }
        } finally {
            if (interrupted) Thread.currentThread().interrupt();
        }
    }

    /**
     * IO 组诊断，线程安全、不可变。
     * @param transport 实际传输。
     * @param bossThreads 接入线程预算，UDP 为零。
     * @param workerThreads 工作线程预算。
     * @param closing 是否已发起关闭。
     * @param terminated 是否已实际结束。
     * @author zn
     */
    public record Snapshot(NetworkTransport transport, int bossThreads, int workerThreads,
            boolean closing, boolean terminated) { }
}
