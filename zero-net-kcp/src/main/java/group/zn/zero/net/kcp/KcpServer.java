package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.time.Clock;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * TCP 授权的 KCP 服务；一个 socket/EventLoop 串行管理算法，无第三方线程池或全局定时器。
 * 业务回调使用显式非内联执行器；IO 可独占或借用。构造不创建线程，start/stop 由组合根调用。
 * @author zn
 */
public final class KcpServer extends AbstractLifecycle implements IServer {
    /** 网络配置。 */
    private final ServerOptions options;
    /** KCP 设置。 */
    private final KcpOptions config;
    /** 策略注册表。 */
    private final KcpAlgorithms algorithms;
    /** 节点身份和可替换所有权存储，生命周期由组合根拥有。 */
    private final KcpSessionServices sessionServices;
    /** 业务帧 codec，由单 EventLoop 使用。 */
    private final ProtocolFrameCodec codec;
    /** 业务处理器。 */
    private final ServerFrameHandler handler;
    /** 生命周期观察器。 */
    private final ConnectionListener listener;
    /** 调用方拥有的业务执行器。 */
    private final Executor executor;
    /** 调用方拥有的可选 IO 组。 */
    private final NettyIoResources borrowed;
    /** 本次 IO 资源。 */
    private NettyIoResources resources;
    /** 本次绑定 socket，跨线程可见。 */
    private volatile Channel channel;
    /** 会话注册表，仅 EventLoop 修改。 */
    private KcpSessions sessions;
    /** 独立可观测计数。 */
    private final KcpCounters counters = new KcpCounters();
    /** 本轮等待刷新数据报数，仅 EventLoop 修改。 */
    private int unflushed;
    /** 已提交一个批次末刷新任务。 */
    private boolean flushScheduled;
    /** 可选有界指标订阅。 */
    private volatile KcpWatch watch;
    /** 发送总预算。 */
    private final AtomicLong pendingBytes = new AtomicLong();
    /** 入站业务总预算，含关闭后尚未完成的业务。 */
    private final AtomicLong inboundBytes = new AtomicLong();
    /** FEC 共享缓存预算。 */
    private final AtomicLong fecBytes = new AtomicLong();
    /** 未经调度的授权请求预算，避免无界任务提交。 */
    private final AtomicInteger pendingTickets = new AtomicInteger();
    /** 丢弃或拒绝计数。 */
    private final AtomicLong rejected = new AtomicLong();
    /** 异常观察计数，错误监听器异常也可见。 */
    private final AtomicLong failures = new AtomicLong();

    /**
     * 创建服务；线程安全，无 IO 副作用，所有传入对象须非空（io 除外）。
     * @param options KCP 网络设置。
     * @param config KCP 安全与容量配置。
     * @param codec 帧 codec，必须自持有解码结果。
     * @param handler 有序业务处理器。
     * @param listener 非阻塞观察器；open/close 在业务执行器，exception 在调用线程。
     * @param executor 非内联执行器，由调用方关闭。
     * @param io 可借用 IO 组；null 表示本服务拥有资源。
     * @throws IllegalArgumentException 网络类型不是 KCP。
     */
    public KcpServer(final ServerOptions options, final KcpOptions config, final ProtocolFrameCodec codec,
            final ServerFrameHandler handler, final ConnectionListener listener, final Executor executor,
            final NettyIoResources io) {
        this(options, config, codec, handler, listener, executor, io, KcpAlgorithms.defaults());
    }

    /** 自定义算法装配；注册表必须在启动前完整校验。 */
    public KcpServer(final ServerOptions options, final KcpOptions config, final ProtocolFrameCodec codec,
            final ServerFrameHandler handler, final ConnectionListener listener, final Executor executor,
            final NettyIoResources io, final KcpAlgorithms algorithms) {
        this(options, config, codec, handler, listener, executor, io, algorithms, KcpSessionServices.local());
    }

    /**
     * 集群装配；构造无 IO 副作用。store 为非阻塞、线性化 SPI，资源由组合根关闭。
     * @param sessionServices 唯一节点 ID、存储、租约和操作期限；不可为空。
     * @param algorithms 启动前完整注册的算法策略；其余参数同基础构造器。
     * @throws IllegalArgumentException 配置不合法；线程安全，只初始化本实例。
     */
    public KcpServer(final ServerOptions options, final KcpOptions config, final ProtocolFrameCodec codec,
            final ServerFrameHandler handler, final ConnectionListener listener, final Executor executor,
            final NettyIoResources io, final KcpAlgorithms algorithms, final KcpSessionServices sessionServices) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.KCP) throw new IllegalArgumentException("KCP options required");
        this.config = Objects.requireNonNull(config, "config");
        this.codec = Objects.requireNonNull(codec, "codec");
        this.handler = Objects.requireNonNull(handler, "handler");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.algorithms = Objects.requireNonNull(algorithms, "algorithms");
        this.sessionServices = Objects.requireNonNull(sessionServices, "sessionServices");
        algorithms.validate(config);
        borrowed = io;
    }
    /** @return 配置或已绑定地址；线程安全，无变更。 */
    @Override public String bindAddress() {
        Channel current = channel;
        return current == null || !current.isActive() ? options.bindAddress() : current.localAddress().toString();
    }
    /** @return 实际端口，未启动时返回配置端口；线程安全，无变更。 */
    public int boundPort() {
        Channel current = channel;
        return current == null || !current.isActive() ? options.port() : ((InetSocketAddress) current.localAddress()).getPort();
    }
    /** @return KCP；线程安全，无变更。 */
    @Override public ServerType serverType() { return ServerType.KCP; }
    /** @return 拒绝包/拥塞丢包计数；线程安全，无变更。 */
    public long rejectedDatagrams() { return rejected.get(); }
    /** @return 异常计数；线程安全，无变更。 */
    public long failureCount() { return failures.get(); }
    /** @return 所有未释放的发送预留字节；线程安全，无变更。 */
    public long pendingSendBytes() { return pendingBytes.get(); }
    /** @return 待执行及未完成业务持有的预算；线程安全，无变更。 */
    public long pendingInboundBytes() { return inboundBytes.get(); }
    /** @return FEC 当前缓存字节；线程安全。 */
    public long pendingFecBytes() { return fecBytes.get(); }

    /** @return 不含密钥的有效配置；不可变、线程安全。 */
    public KcpOptions configuration() { return config; }
    /**
     * 创建可信控制面描述；不分配框架业务协议 ID，调用者负责经原 TLS 连接发送结果。
     * @param control 认证 TCP 连接。
     * @param host 对外主机地址。
     * @param port 对外 UDP 端口，可不同于 NAT 后的本地端口。
     * @return 授权描述；线程安全，认证或端点不合法时以异常完成。
     */
    public CompletionStage<KcpConnectInfo> issueConnectInfo(final IConnection control, final String host, final int port) {
        KcpLimits limits = config.limits();
        KcpOptions effective = config.toBuilder().limits(new KcpLimits(limits.maxSessions(), limits.maxQueuedFrames(),
                limits.maxPendingSegments(), maxFrame(), limits.maxInboundBytesTotal())).build();
        // 先验证端点，再签发票据，避免无效配置撤销现有连接。
        new KcpConnectInfo(host, port, new KcpTicket(1, new byte[32], java.time.Instant.EPOCH), effective);
        return issueTicket(control).thenApply(ticket -> new KcpConnectInfo(host, port, ticket, effective));
    }
    /** @return 独立近实时指标快照；线程安全，无外部变更。 */
    public KcpSnapshot snapshot() {
        return new KcpSnapshot(counters.sessions.get(), counters.connected.get(), counters.received.get(),
                counters.sent.get(), rejected.get(), failures.get(), counters.updates.get(), counters.flushes.get(),
                pendingBytes.get(), inboundBytes.get(), counters.reasons());
    }
    /**
     * 安装唯一指标订阅；线程安全，必须在启动后调用，stop 自动取消。
     * @param interval 100ms..1min 采样间隔。
     * @param worker 非内联、由组合根拥有的观察器执行器。
     * @param observer 非阻塞指标消费者，不应保存密钥或高基数标签。
     * @return 可取消订阅；非法配置/状态抛出参数或状态异常。
     */
    public synchronized KcpWatch watch(final java.time.Duration interval, final Executor worker,
            final java.util.function.Consumer<KcpSnapshot> observer) {
        Objects.requireNonNull(interval); Objects.requireNonNull(worker); Objects.requireNonNull(observer);
        if (interval.toMillis() < 100 || interval.compareTo(java.time.Duration.ofMinutes(1)) > 0) {
            throw new IllegalArgumentException("KCP observation interval must be 100ms..1min");
        }
        if (channel == null || !channel.isActive() || watch != null) {
            throw new IllegalStateException("KCP not running or observer already installed");
        }
        watch = new KcpWatch(this, interval, worker, observer);
        return watch;
    }

    /**
     * TCP 登录成功后申请票据；检查生产状态、主体、有效期与 TLS，随后只经控制通道下发。
     * 同一控制连接重新申请会撤销旧票据。线程安全，有限请求队列。
     * @param control 已认证 TCP 连接，生命周期属性须由框架设置。
     * @return 票据完成信号；未启动、未授权或容量耗尽以 ZeroException 完成。
     */
    public CompletionStage<KcpTicket> issueTicket(final IConnection control) {
        if (pendingTickets.incrementAndGet() > config.maxSessions()) {
            pendingTickets.decrementAndGet();
            return CompletableFuture.failedFuture(error(NetErrorCode.RATE_LIMITED, null));
        }
        CompletableFuture<KcpTicket> result = new CompletableFuture<>();
        Channel current = channel;
        if (current == null || !current.isActive()) {
            pendingTickets.decrementAndGet();
            return CompletableFuture.failedFuture(error(NetErrorCode.INVALID_LIFECYCLE_STATE, null));
        }
        try {
            current.eventLoop().execute(() -> {
                try {
                    if (!current.isActive() || current != channel) throw error(NetErrorCode.INVALID_LIFECYCLE_STATE, null);
                    sessions.issue(control).whenComplete((ticket, failure) -> {
                        pendingTickets.decrementAndGet();
                        if (failure == null) result.complete(ticket); else result.completeExceptionally(failure);
                    });
                } catch (RuntimeException failure) { pendingTickets.decrementAndGet(); result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) {
            pendingTickets.decrementAndGet();
            result.completeExceptionally(error(NetErrorCode.INVALID_LIFECYCLE_STATE, failure));
        }
        return result.minimalCompletionStage();
    }

    /**
     * 显式回退屏障：撤销会话并清除 KCP 队列，随后调用方才可在 TCP 提交新请求。
     * 不自动重放；屏障不取消已经执行的业务，调用方仍须使用请求 ID 去重。线程安全、幂等。
     * @param control 发起回退的已认证 TCP 控制连接，必须是票据的拥有者。
     * @param conv 票据会话号。
     * @return 本地撤销完成信号；不是远端已停发或业务事务完成的确认。
     */
    public CompletionStage<Void> fallbackToTcp(final IConnection control, final int conv) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Channel current = channel;
        if (current == null || !current.isActive()) return CompletableFuture.completedFuture(null);
        try {
            current.eventLoop().execute(() -> {
                try { sessions.fallback(control, conv); result.complete(null); }
                catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(error(NetErrorCode.STOP_FAILED, failure)); }
        return result.minimalCompletionStage();
    }

    @Override protected void beforeStartRequest() {
        if (state() != group.zn.zero.core.lifecycle.LifecycleState.NEW
                && state() != group.zn.zero.core.lifecycle.LifecycleState.RUNNING) {
            throw error(NetErrorCode.INVALID_LIFECYCLE_STATE, null);
        }
    }

    /**
     * 经可信控制面将会话转给指定节点；线程安全，不阻塞调用线程。
     * 客户端必须先 quiesce 并确认业务响应；冻结后只排空已接收业务，失败关闭，不自动重放。
     * @param control 票据所属已认证 TCP 连接。 @param conv 会话号。 @param targetNode 目标唯一节点名。
     * @param drainTimeout 排空上限，1ms..30s。 @return 可经 TLS 下发的迁移路由，冲突/超时异常完成。
     */
    public CompletionStage<KcpHandoff> migrate(final IConnection control, final int conv,
            final String targetNode, final java.time.Duration drainTimeout) {
        return controlOperation(() -> sessions.migrate(control, conv, targetNode, drainTimeout));
    }
    /**
     * 目标节点验证登录主体、CAS 接管并签发新密钥，原 conv 保留且 generation 增加；线程安全。
     * @param control 目标节点已认证 TCP 连接。 @param handoff 源节点迁移结果，不是身份凭证。
     * @param host 对外地址。 @param port UDP 端口。 @return 新描述，只经 TLS 发送；重复接管异常完成。
     */
    public CompletionStage<KcpConnectInfo> claimConnectInfo(final IConnection control, final KcpHandoff handoff,
            final String host, final int port) {
        Objects.requireNonNull(handoff);
        new KcpConnectInfo(host, port, new KcpTicket(1, new byte[32], java.time.Instant.EPOCH), config);
        return controlOperation(() -> sessions.claim(control, handoff)).thenApply(ticket -> new KcpConnectInfo(host, port, ticket, effectiveOptions()));
    }
    private KcpOptions effectiveOptions() {
        KcpLimits limits = config.limits();
        return config.toBuilder().limits(new KcpLimits(limits.maxSessions(), limits.maxQueuedFrames(),
                limits.maxPendingSegments(), maxFrame(), limits.maxInboundBytesTotal())).build();
    }
    private <T> CompletionStage<T> controlOperation(final java.util.function.Supplier<CompletionStage<T>> operation) {
        if (pendingTickets.incrementAndGet() > config.maxSessions()) {
            pendingTickets.decrementAndGet(); return CompletableFuture.failedFuture(error(NetErrorCode.RATE_LIMITED, null));
        }
        var result = new CompletableFuture<T>();
        result.whenComplete((value, failure) -> pendingTickets.decrementAndGet());
        Channel current = channel;
        if (current == null || !current.isActive()) result.completeExceptionally(error(NetErrorCode.INVALID_LIFECYCLE_STATE, null));
        else try {
            current.eventLoop().execute(() -> {
                try {
                    if (!current.isActive()) throw error(NetErrorCode.INVALID_LIFECYCLE_STATE, null);
                    operation.get().whenComplete((value, failure) -> {
                        if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                    });
                } catch (RuntimeException failure) { result.completeExceptionally(failure); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result.minimalCompletionStage();
    }
    @Override protected void doStart() {
        try {
            resources = borrowed == null ? NettyIoResources.open(options) : borrowed;
            sessions = new KcpSessions(this, Clock.systemUTC());
            var binding = resources.bindDatagram(options, new Inbound());
            channel = binding.channel();
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException("KCP bind interrupted");
            binding.sync();
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            try { doStop(); } catch (RuntimeException cleanup) { failure.addSuppressed(cleanup); }
            throw error(NetErrorCode.START_FAILED, failure);
        }
    }
    @Override protected synchronized void doStop() {
        if (watch != null) watch.close();
        Channel current = channel;
        try {
            if (current != null) {
                var closing = current.close();
                if (!current.eventLoop().inEventLoop()) closing.syncUninterruptibly();
            }
        } finally {
            if (borrowed == null && resources != null) resources.close();
        }
    }
    CompletionStage<Void> closeSession(final KcpSession session) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        Channel current = channel;
        if (current == null || !current.isActive()) return CompletableFuture.completedFuture(null);
        try {
            current.eventLoop().execute(() -> {
                try { sessions.removeCurrent(session); result.complete(null); }
                catch (RuntimeException failure) { result.completeExceptionally(error(NetErrorCode.STOP_FAILED, failure)); }
            });
        } catch (RuntimeException failure) { result.completeExceptionally(error(NetErrorCode.STOP_FAILED, failure)); }
        return result.minimalCompletionStage();
    }
    void fail(final KcpSession session, final NetErrorCode code, final Throwable failure) {
        sessions.removeCurrent(session, code.name());
        observeFailure(session.connection, code, failure);
    }
    void closeWithFailure(final KcpSession session, final NetErrorCode code, final Throwable failure) {
        // 总是排队，避免算法 output 回调重入释放正在遍历的 segment。
        try { channel.eventLoop().execute(() -> fail(session, code, failure)); }
        catch (RuntimeException rejection) { observeFailure(session.connection, code, rejection); }
    }
    void observeFailure(final IConnection connection, final NetErrorCode code, final Throwable failure) {
        failures.incrementAndGet();
        ZeroException error = error(code, failure);
        try { listener.onException(connection, error); }
        catch (RuntimeException observerFailure) {
            failures.incrementAndGet();
            observerFailure.addSuppressed(error);
            System.getLogger(KcpServer.class.getName()).log(System.Logger.Level.ERROR,
                    NetErrorCode.OBSERVER_FAILED.code(), observerFailure);
        }
    }
    KcpBudget newBudget() {
        return new KcpBudget(pendingBytes, options.tuning().maxPendingBytesTotal(),
                Math.min(options.tuning().maxPendingBytesPerConnection(),
                        (long) config.maxPendingSegments() * (config.mtu() + 128)));
    }
    long sendCharge(final group.zn.zero.protocol.ProtocolFrame frame) {
        // 只在调用线程使用已知线程安全的精确尺寸实现；自定义 codec 仍由 IO 独占。
        int size = codec instanceof group.zn.zero.protocol.codec.ZeroBinaryFrameCodec ? codec.encodedLength(frame) : maxFrame();
        if (size <= 0 || size > maxFrame()) throw error(NetErrorCode.INVALID_MESSAGE, null);
        return ((size + config.mtu() - 25L) / (config.mtu() - 24)) * (config.mtu() + 128L);
    }
    int maxFrame() { return Math.min(options.maxFrameLength(), config.maxMessageBytes()); }
    Channel channel() { return channel; }
    KcpOptions config() { return config; }
    KcpAlgorithms algorithms() { return algorithms; }
    KcpSessionServices sessionServices() { return sessionServices; }
    boolean reserveFec(final long bytes) { return KcpBudget.reserve(fecBytes, config.transport().maxFecBytesTotal(), bytes); }
    void releaseFec(final long bytes) { fecBytes.addAndGet(-bytes); }
    ProtocolFrameCodec codec() { return codec; }
    ServerFrameHandler handler() { return handler; }
    ConnectionListener listener() { return listener; }
    Executor executor() { return executor; }
    KcpCounters counters() { return counters; }
    void schedule(final KcpSession session) { sessions.schedule(session); }
    void write(final DatagramPacket packet, final KcpSession session) {
        counters.sent.incrementAndGet();
        channel.write(packet).addListener(done -> {
            if (!done.isSuccess()) closeWithFailure(session, NetErrorCode.SEND_FAILED, done.cause());
        });
        if (++unflushed >= config.tuning().flushBatch()) flush();
        else if (!flushScheduled) {
            flushScheduled = true;
            channel.eventLoop().execute(() -> { flushScheduled = false; flush(); });
        }
    }
    private void flush() {
        if (unflushed > 0) { unflushed = 0; counters.flushes.incrementAndGet(); channel.flush(); }
    }
    void rejected() { rejected.incrementAndGet(); }
    void rejected(final String reason) { rejected(); counters.reason("reject." + reason); }
    boolean reserveInbound(final long bytes) {
        return KcpBudget.reserve(inboundBytes, config.maxInboundBytesTotal(), bytes);
    }
    void releaseInbound(final long bytes) { inboundBytes.addAndGet(-bytes); }
    static ZeroException error(final NetErrorCode code, final Throwable cause) {
        return ZeroException.of(code, code.message(), cause);
    }

    /** socket 入站；无效源包隔离，不影响其他会话。 @author zn */
    private final class Inbound extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override protected void channelRead0(final ChannelHandlerContext context, final DatagramPacket packet) {
            counters.received.incrementAndGet();
            if (packet.content().readableBytes() > config.transport().maxDatagramBytes()) rejected("oversize");
            else sessions.input(packet);
        }
        @Override public void channelInactive(final ChannelHandlerContext context) {
            if (watch != null) watch.close();
            sessions.close();
        }
        @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable failure) {
            observeFailure(null, NetErrorCode.INVALID_MESSAGE, failure);
        }
    }
}
