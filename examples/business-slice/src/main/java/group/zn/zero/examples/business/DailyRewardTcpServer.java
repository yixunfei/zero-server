package group.zn.zero.examples.business;

import group.zn.zero.game.RequestExecution;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFactory;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.security.SecurityChain;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.error.NetErrorCode;

/** 奖励业务真实 loopback TCP 配方；强制注入认证链，复用框架握手和受管执行器。 @author zn */
public final class DailyRewardTcpServer implements AutoCloseable {
    /** 会话中的在途交付，仅保存当前连接；断连清理。 */
    private final ConcurrentHashMap<String, Session> sessions = new ConcurrentHashMap<>();
    /** 底层网络资源。 */
    private final IServer server;
    /** 首个传输失败；关闭时传播，避免异步回调仅返回一个无人观察的失败阶段。 */
    private final AtomicReference<ZeroException> transportFailure = new AtomicReference<>();

    /** 构造不启动；安全链验证握手凭据后才接受奖励请求。
     * @param flow local 共用业务入口。 @param security 外部认证/重放策略。 @param executors 框架资源。
     * @throws RuntimeException 配置非法或端口构造失败。 */
    public DailyRewardTcpServer(final DailyRewardFlow flow, final SecurityChain security,
            final ZeroRuntimeExecutors executors) {
        java.util.Objects.requireNonNull(security, "security");
        var lifecycle = new ProductionNetworkLifecycle(ProductionNetworkConfig.defaults("daily-reward").withHeartbeatEnabled(false),
                (connection, frame) -> frame.protocolId() == 1 && frame.protocolVersion() == 1
                        ? NetworkAdmissionDecision.allow() : NetworkAdmissionDecision.rejected(
                                group.zn.zero.net.error.NetErrorCode.AUTHENTICATION_REJECTED,
                                group.zn.zero.net.lifecycle.ConnectionRejectionReason.AUTHENTICATION_REJECTED),
                security, NetworkRateLimiter.permitAll(), ConnectionLifecycleObserver.noOp(),
                executors.remoteIoExecutor(), executors.remoteIoExecutor());
        server = ServerFactory.tcp(ServerOptions.tcp("127.0.0.1", 0).withIoThreads(1, 1), new ZeroBinaryFrameCodec(),
                (connection, frame) -> {
                    var session = sessions.get(connection.connectionId());
                    var scope = RequestExecution.capture(Duration.ofSeconds(3));
                    if (session == null || !session.begin(scope)) return CompletableFuture.completedFuture(List.of());
                    return flow.handle(frame, scope).thenApply(List::of)
                            .whenComplete((ignored, failure) -> session.finish(scope));
                }, new ConnectionListener() {
                    @Override public void onOpen(final IConnection connection) {
                        sessions.put(connection.connectionId(), new Session());
                        // 握手就绪帧让客户端无需定时等待或猜测鉴权是否完成。
                        connection.sendFrame(new ProtocolFrame(1, 1, 0, null, new byte[0]))
                                .whenComplete((ignored, failure) -> {
                                    if (failure != null) reject(connection, failure);
                                });
                    }
                    @Override public void onClose(final IConnection connection) {
                        Session session = sessions.remove(connection.connectionId());
                        if (session != null) session.close();
                    }
                    @Override public void onException(final IConnection connection, final Throwable cause) {
                        reject(connection, cause);
                    }
                }, executors.logicExecutor(), lifecycle);
    }

    /** 启动真实监听；失败直接传播。 */
    public void start() { server.start(); }
    /** @return 实际地址；只读、线程安全。 */
    public String bindAddress() { return server.bindAddress(); }
    /** @return 首个传输错误；只读、线程安全，正常运行时 empty，可用于健康检查。 */
    public java.util.Optional<ZeroException> failure() { return java.util.Optional.ofNullable(transportFailure.get()); }
    /** 取消连接交付并关闭监听；共享 runtime 应在之后关闭。 */
    @Override public void close() {
        sessions.values().forEach(Session::close);
        server.stop();
        sessions.clear();
        ZeroException failure = transportFailure.get();
        if (failure != null) throw failure;
    }

    private void reject(final IConnection connection, final Throwable cause) {
        ZeroException failure = cause instanceof ZeroException error ? error
                : ZeroException.of(NetErrorCode.HANDLER_FAILED, "reward transport failed", cause);
        transportFailure.compareAndSet(null, failure);
        if (connection == null) return;
        Session session = sessions.remove(connection.connectionId());
        if (session != null) session.close();
        connection.close().whenComplete((ignored, closeFailure) -> {
            if (closeFailure != null) failure.addSuppressed(closeFailure);
        });
    }

    /** 断连与新请求准入线性化的会话范围。 @author zn */
    private static final class Session {
        /** 在途范围。 */
        private final Set<RequestExecution> requests = new HashSet<>();
        /** 已断连。 */
        private boolean closed;
        private synchronized boolean begin(final RequestExecution scope) {
            if (closed) { scope.cancel(); return false; }
            requests.add(scope); return true;
        }
        private synchronized void finish(final RequestExecution scope) { requests.remove(scope); }
        private synchronized void close() { closed = true; requests.forEach(RequestExecution::cancel); requests.clear(); }
    }
}
