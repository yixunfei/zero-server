package group.zn.zero.net.kcp;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * 显式恢复/回退编排；线程安全，转换期间拒绝新提交，不重放旧请求。
 * acquire/revoke 的远程超时由控制面适配口保证；close 可在等待远程结果时本地终止。
 * @author zn
 */
public final class KcpRecovery {
    /** 可观察状态。 @author zn */
    public enum State { NEW, CONNECTING, KCP, FALLING_BACK, TCP, FAILED, CLOSED }
    /** TLS 控制面。 */
    private final KcpControlPlane control;
    /** 框架业务资源。 */
    private final Executor executor;
    /** 业务入口。 */
    private final ServerFrameHandler handler;
    /** 连接观察器。 */
    private final ConnectionListener listener;
    /** 可借用 IO。 */
    private final NettyIoResources io;
    /** 新连接始终使用同一不可变算法表，禁止恢复时降级策略。 */
    private final KcpAlgorithms algorithms;
    /** 当前连接，在锁内发布。 */
    private KcpClient client;
    /** 当前状态。 */
    private State state = State.NEW;
    /** 操作代际，关闭使在途结果无效。 */
    private long generation;
    /**
     * 创建恢复管理器，不创建资源。
     * @param control 已认证控制面。 @param executor 非内联框架执行器。
     * @param handler 业务入口。 @param listener 观察器。 @param io 可借用资源。
     */
    public KcpRecovery(final KcpControlPlane control, final Executor executor, final ServerFrameHandler handler,
            final ConnectionListener listener, final NettyIoResources io) {
        this(control, executor, handler, listener, io, KcpAlgorithms.defaults());
    }
    /**
     * 创建支持自定义策略的恢复管理器；线程安全，不创建资源。
     * @param control 已认证且有限超时的控制面。 @param executor 非内联业务执行器。
     * @param handler 业务入口。 @param listener 连接观察器。 @param io 可借用 IO，允许 null。
     * @param algorithms 与服务端一致的不可变算法表；未知策略在连接创建前拒绝。
     * @throws NullPointerException 必需依赖为空。
     */
    public KcpRecovery(final KcpControlPlane control, final Executor executor, final ServerFrameHandler handler,
            final ConnectionListener listener, final NettyIoResources io, final KcpAlgorithms algorithms) {
        this.control = Objects.requireNonNull(control); this.executor = Objects.requireNonNull(executor);
        this.handler = Objects.requireNonNull(handler); this.listener = Objects.requireNonNull(listener); this.io = io;
        this.algorithms = Objects.requireNonNull(algorithms);
    }
    /** @return 当前状态；线程安全，无变更。 */
    public synchronized State state() {
        if (state == State.KCP && client != null && client.state() == KcpClient.State.CLOSED) state = State.FAILED;
        return state;
    }
    /** @return 建立新连接信号；线程安全，网络切换也使用此入口；并发转换拒绝。 */
    public synchronized CompletionStage<KcpClient> reconnect() {
        if (busy() || state == State.CLOSED) return failed();
        state = State.CONNECTING;
        long current = ++generation;
        CompletionStage<KcpClient> result;
        try {
            result = revokeCurrent().thenCompose(ignored -> control.acquire()).thenComposeAsync(info -> {
                synchronized (this) {
                    if (current != generation) return control.revoke(info.ticket().conv()).thenCompose(ignored -> failed());
                    client = new KcpClient(info, executor, handler, listener, io, algorithms);
                    return client.connect();
                }
            }, executor);
        } catch (RuntimeException failure) { state = State.FAILED; return CompletableFuture.failedFuture(failure); }
        return result.whenComplete((connection, failure) -> {
            synchronized (this) { if (generation == current) state = failure == null ? State.KCP : State.FAILED; }
        });
    }
    /** @param frame 新业务帧。 @return 本地入队信号；转换期间拒绝，不缓存或重放；线程安全。 */
    public synchronized CompletionStage<Void> send(final ProtocolFrame frame) {
        if (state() != State.KCP || client == null) return failed();
        return client.sendFrame(frame);
    }
    /** @return 先停本地提交再撤销服务器票据的屏障，完成后新业务可走 TCP；线程安全。 */
    public synchronized CompletionStage<Void> fallbackToTcp() {
        if (state == State.TCP) return CompletableFuture.completedFuture(null);
        if (busy() || state == State.CLOSED) return failed();
        state = State.FALLING_BACK;
        long current = ++generation;
        try {
            return revokeCurrent().whenComplete((ignored, failure) -> {
                synchronized (this) { if (generation == current) state = failure == null ? State.TCP : State.FAILED; }
            });
        } catch (RuntimeException failure) { state = State.FAILED; return CompletableFuture.failedFuture(failure); }
    }
    /** @return 本地关闭信号；不依赖可用的控制通道；线程安全、幂等。 */
    public synchronized CompletionStage<Void> close() {
        state = State.CLOSED; generation++;
        return client == null ? CompletableFuture.completedFuture(null) : client.close();
    }
    private CompletionStage<Void> revokeCurrent() {
        KcpClient old = client;
        return old == null ? CompletableFuture.completedFuture(null)
                : old.close().thenCompose(ignored -> control.revoke(old.connectInfo().ticket().conv()));
    }
    private boolean busy() { return state == State.CONNECTING || state == State.FALLING_BACK; }
    private static <T> CompletionStage<T> failed() {
        return CompletableFuture.failedFuture(KcpServer.error(NetErrorCode.INVALID_LIFECYCLE_STATE, null));
    }
}
