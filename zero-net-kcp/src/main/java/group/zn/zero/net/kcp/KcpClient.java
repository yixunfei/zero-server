package group.zn.zero.net.kcp;

import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import kcp.Kcp;

/**
 * 受管 Java 客户端，connect/send/close 线程安全；算法和定时器由单 EventLoop 独占。
 * 描述必须来自已认证 TLS 控制面。send 仅确认本地入队；不会重放失败请求。
 * @author zn
 */
public final class KcpClient implements IConnection, KcpDispatchEndpoint {
    /** 连接状态；关闭后的对象不可重用。 @author zn */
    public enum State { NEW, CONNECTING, CONNECTED, CLOSED }
    /** 不可变控制面描述。 */
    private final KcpConnectInfo info;
    /** 解析后的唯一服务端地址。 */
    private final InetSocketAddress remote;
    /** 业务执行器，由组合根拥有。 */
    private final Executor executor;
    /** 业务处理器。 */
    private final ServerFrameHandler handler;
    /** 观察器，异常回调必须非阻塞。 */
    private final ConnectionListener listener;
    /** 借用资源，可为空。 */
    private final NettyIoResources borrowed;
    /** 本连接资源。 */
    private NettyIoResources resources;
    /** 私有默认帧 codec。 */
    private final ProtocolFrameCodec codec = new ZeroBinaryFrameCodec();
    /** 私有认证状态。 */
    private final KcpTransport transport;
    /** 线程安全属性。 */
    private final ConnectionAttributes attributes = new DefaultConnectionAttributes();
    /** 跨线程生命周期。 */
    private final AtomicReference<State> state = new AtomicReference<>(State.NEW);
    /** 握手信号。 */
    private final CompletableFuture<KcpClient> ready = new CompletableFuture<>();
    /** 关闭 socket 信号，不包含未完成业务事务。 */
    private final CompletableFuture<Void> stopped = new CompletableFuture<>();
    /** 发送内存。 */
    private final AtomicLong pending = new AtomicLong();
    /** 入站业务内存。 */
    private final AtomicLong inbound = new AtomicLong();
    /** FEC 缓存，与业务预算分离。 */
    private final AtomicLong fecBytes = new AtomicLong();
    /** 换端口候选 socket，使用同一 EventLoop。 */
    private Channel candidate;
    /** 候选路径完成信号。 */
    private CompletableFuture<Void> rebinding;
    /** 路径验证总期限。 */
    private long rebindUntil;
    /** 候选最近探测时间。 */
    private long probed;
    /** 候选已响应服务端挑战。 */
    private boolean responded;
    /** 提交前准入。 */
    private final KcpBudget budget;
    /** 共用有序业务调度。 */
    private final KcpDispatch dispatch;
    /** 私有 socket，跨线程可见。 */
    private volatile Channel channel;
    /** 算法仅在 IO 访问。 */
    private Kcp engine;
    /** 每连接唯一计时器。 */
    private ScheduledFuture<?> timer;
    /** 当前计时器期限，避免每个数据报重新分配定时任务。 */
    private long armed;
    /** 单调起点。 */
    private final long origin = System.nanoTime();
    /** 墙钟起点，用于上游时间表示。 */
    private final long epoch = System.currentTimeMillis();
    /** 握手开始时间。 */
    private long started;
    /** 最后有效数据报。 */
    private long lastSeen;
    /** 上次 hello/心跳。 */
    private long lastHeartbeat;
    /** 最近一次发送进展。 */
    private long progress;
    /** 调用方暂停业务发送，不影响协议 ACK 和已接收 handler 响应。 */
    private volatile boolean quiescing;
    /** 双端排空信号，受 connect/close 相同同步锁保护写入。 */
    private CompletableFuture<Void> quiesced;
    /** 单调排空期限。 */
    private long quiesceUntil;
    /** 最近一次请求服务端排空的时间。 */
    private long quiesceProbe;
    /** 已认证服务端排空确认。 */
    private boolean peerDrained;

    /**
     * 创建独立客户端；不创建线程，解析 endpoint 可能阻塞 DNS，应在组合根调用。
     * @param info 可信连接描述。
     * @param executor 调用方拥有的非内联业务执行器。
     * @param handler 有序异步帧处理器，可返回响应帧。
     * @param listener 有序生命周期/非阻塞错误观察器。
     * @param io 可借用 IO，null 表示 connect 创建自有资源。
     * @throws IllegalArgumentException 端点不可解析。
     */
    public KcpClient(final KcpConnectInfo info, final Executor executor, final ServerFrameHandler handler,
            final ConnectionListener listener, final NettyIoResources io) {
        this(info, executor, handler, listener, io, KcpAlgorithms.defaults());
    }
    /** 自定义算法装配；其余参数和资源所有权同主构造器，启动前校验且不创建线程。 */
    public KcpClient(final KcpConnectInfo info, final Executor executor, final ServerFrameHandler handler,
            final ConnectionListener listener, final NettyIoResources io, final KcpAlgorithms algorithms) {
        algorithms.validate(info.options());
        this.info = Objects.requireNonNull(info); remote = info.address();
        this.executor = Objects.requireNonNull(executor); this.handler = Objects.requireNonNull(handler);
        this.listener = Objects.requireNonNull(listener); borrowed = io;
        transport = new KcpTransport(info.ticket(), false, info.options(), algorithms,
                bytes -> KcpBudget.reserve(fecBytes, info.options().transport().maxFecBytesTotal(), bytes),
                bytes -> fecBytes.addAndGet(-bytes), reason -> { });
        long capacity = options().maxPendingSegments() * (options().mtu() + 128L);
        budget = new KcpBudget(pending, capacity, capacity);
        dispatch = new KcpDispatch(this);
    }
    /** @return 认证 hello 完成后的连接；线程安全，握手失败异常完成，可重复读取同一信号。 */
    public synchronized CompletionStage<KcpClient> connect() {
        if (!state.compareAndSet(State.NEW, State.CONNECTING)) return ready.minimalCompletionStage();
        try {
            if (!info.ticket().expiresAt().isAfter(Instant.now())) throw KcpServer.error(NetErrorCode.AUTHENTICATION_EXPIRED, null);
            String local = remote.getAddress() instanceof java.net.Inet6Address ? "::" : "0.0.0.0";
            ServerOptions socket = ServerOptions.kcp(local, 0).withIoThreads(1, 1);
            resources = borrowed == null ? NettyIoResources.open(socket) : borrowed;
            var binding = resources.bindDatagram(socket, new Inbound());
            channel = binding.channel();
            binding.addListener(done -> {
                if (!done.isSuccess()) fail(NetErrorCode.START_FAILED, done.cause());
                else if (closed()) shutdown();
                else begin();
            });
        } catch (RuntimeException failure) { fail(NetErrorCode.START_FAILED, failure); }
        return ready.minimalCompletionStage();
    }
    private void begin() {
        engine = new Kcp(info.ticket().conv(), (body, ignored) -> output(body));
        options().tuning().apply(engine);
        engine.update(millis());
        started = System.nanoTime(); lastSeen = started; progress = started;
        heartbeat(); schedule();
    }
    /** @return 当前状态；线程安全。 */
    public State state() { return state.get(); }
    /** @return 当前发送预算；线程安全。 */
    public long pendingSendBytes() { return pending.get(); }
    /** @return 当前入站预算，关闭后仍可能由未完成 handler 持有；线程安全。 */
    public long pendingInboundBytes() { return inbound.get(); }
    /** @return 不可变控制面描述，toString 不含密钥；线程安全。 */
    public KcpConnectInfo connectInfo() { return info; }
    /** @return 不可变唯一标识；线程安全。 */
    @Override public String connectionId() { return "kcp-client-" + Integer.toUnsignedString(info.ticket().conv()); }
    /** @return KCP；线程安全。 */
    @Override public ServerType serverType() { return ServerType.KCP; }
    /** @return 固定目标地址；线程安全。 */
    @Override public SocketAddress remoteAddress() { return remote; }
    /** @return 本地地址，未绑定时为 null；线程安全。 */
    @Override public SocketAddress localAddress() { Channel socket = channel; return socket == null ? null : socket.localAddress(); }
    /** @return 独立线程安全属性；不保存密钥。 */
    @Override public ConnectionAttributes attributes() { return attributes; }
    /** @param message 不可变 ProtocolFrame。 @return 本地入队信号，背压/状态错误异常完成；线程安全。 */
    @Override public CompletionStage<Void> send(final Object message) {
        return sendMessage(message, false);
    }
    @Override public CompletionStage<Void> respond(final ProtocolFrame frame) { return sendMessage(frame, true); }
    private synchronized CompletionStage<Void> sendMessage(final Object message, final boolean response) {
        if (quiescing && !response) return rejected(NetErrorCode.INVALID_LIFECYCLE_STATE);
        if (!(message instanceof ProtocolFrame frame) || state() != State.CONNECTED) return rejected(NetErrorCode.SEND_FAILED);
        long charge;
        try {
            int size = codec.encodedLength(frame);
            if (size > options().maxMessageBytes()) return rejected(NetErrorCode.INVALID_MESSAGE);
            charge = ((size + options().mtu() - 25L) / (options().mtu() - 24)) * (options().mtu() + 128L);
        } catch (RuntimeException failure) { return CompletableFuture.failedFuture(failure); }
        if (!budget.reserve(charge)) return rejected(NetErrorCode.OUTBOUND_OVERFLOW);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { channel.eventLoop().execute(() -> enqueue(frame, charge, result)); }
        catch (RuntimeException failure) { budget.release(charge); result.completeExceptionally(KcpServer.error(NetErrorCode.SEND_FAILED, failure)); }
        return result.minimalCompletionStage();
    }
    private void enqueue(final ProtocolFrame frame, final long charge, final CompletableFuture<Void> result) {
        boolean transferred = false;
        try {
            if (!authorized()) throw KcpServer.error(NetErrorCode.SEND_FAILED, null);
            byte[] bytes = codec.encode(frame);
            int segments = (bytes.length + options().mtu() - 25) / (options().mtu() - 24);
            if (engine.waitSnd() + segments > options().maxPendingSegments()) throw KcpServer.error(NetErrorCode.OUTBOUND_OVERFLOW, null);
            ByteBuf buffer = new KcpRetainedFrame(channel.alloc(), bytes, () -> budget.release(charge));
            transferred = true;
            if (engine.waitSnd() == 0) progress = System.nanoTime();
            try { if (engine.send(buffer) != 0) throw KcpServer.error(NetErrorCode.SEND_FAILED, null); }
            finally { buffer.release(); }
            engine.flush(false, millis()); schedule(); result.complete(null);
        } catch (RuntimeException failure) {
            if (!transferred) budget.release(charge);
            else closeWithFailure(NetErrorCode.SEND_FAILED, failure);
            result.completeExceptionally(failure);
        }
    }
    private void input(final Channel source, final DatagramPacket packet) {
        if (closed() || engine == null || !packet.sender().equals(remote)) return;
        ByteBuf body = transport.decode(packet.content());
        if (body == null) return;
        try {
            int type = transport.type();
            if (!info.ticket().expiresAt().isAfter(Instant.now())) { fail(NetErrorCode.AUTHENTICATION_EXPIRED, null); return; }
            if (type == KcpDatagramCodec.QUIESCED && source == channel && quiescing && !body.isReadable()) {
                peerDrained = true; lastSeen = System.nanoTime(); checkQuiescence(lastSeen); return;
            }
            if (type == KcpDatagramCodec.CHALLENGE && options().transport().paths().enabled()
                    && body.readableBytes() == KcpPathValidator.CHALLENGE_BYTES) {
                if (source == candidate) responded = true;
                source.writeAndFlush(new DatagramPacket(transport.control(source.alloc(), KcpDatagramCodec.RESPONSE,
                        ByteBufUtil.getBytes(body)), remote));
                return;
            }
            if (source == candidate) {
                if (!responded || type != KcpDatagramCodec.DATA && type != KcpDatagramCodec.FEC) return;
                Channel old = channel; channel = candidate; candidate = null;
                CompletableFuture<Void> completed = rebinding; rebinding = null; old.close(); completed.complete(null);
            }
            if (source != channel || type != KcpDatagramCodec.DATA && type != KcpDatagramCodec.FEC) return;
            lastSeen = System.nanoTime();
            if (state.compareAndSet(State.CONNECTING, State.CONNECTED)) { dispatch.open(); ready.complete(this); }
            if (!closed()) transport.input(type, body, this::inputBody);
        } finally { body.release(); }
        schedule();
    }
    private void inputBody(final ByteBuf body) {
        if (closed() || !body.isReadable()) return;
        if (!KcpSegments.valid(body, info.ticket().conv(), options())) { fail(NetErrorCode.INVALID_MESSAGE, null); return; }
        int before = engine.waitSnd();
        if (engine.input(body, true, millis()) != 0) { fail(NetErrorCode.INVALID_MESSAGE, null); return; }
        engine.flush(true, millis());
        if (engine.waitSnd() < before) progress = System.nanoTime();
        drain();
    }
    /**
     * 更换本地端口并验证路径，保留 KCP 队列、会话 ID 和序号；线程安全，不重放业务。
     * @return 新路径确认信号；超时关闭候选并保留原路径，关闭和并发重绑异常完成。
     */
    public CompletionStage<Void> rebind() {
        if (state() != State.CONNECTED || quiescing || !options().transport().paths().enabled()) return rejected(NetErrorCode.INVALID_LIFECYCLE_STATE);
        CompletableFuture<Void> result = new CompletableFuture<>();
        try { channel.eventLoop().execute(() -> beginRebind(result)); }
        catch (RuntimeException failure) { result.completeExceptionally(failure); }
        return result.minimalCompletionStage();
    }
    private void beginRebind(final CompletableFuture<Void> result) {
        if (closed() || quiescing || rebinding != null) { result.completeExceptionally(KcpServer.error(NetErrorCode.INVALID_LIFECYCLE_STATE, null)); return; }
        rebinding = result; responded = false;
        try {
            var local = (InetSocketAddress) channel.localAddress();
            var binding = new io.netty.bootstrap.Bootstrap().group(channel.eventLoop()).channel(channel.getClass())
                    .handler(new Inbound()).bind(new InetSocketAddress(local.getAddress(), 0));
            candidate = binding.channel();
            rebindUntil = System.nanoTime() + options().transport().paths().timeout().toNanos();
            binding.addListener(done -> {
                if (!done.isSuccess()) failRebind(done.cause());
                else if (closed() || candidate != binding.channel()) binding.channel().close();
                else { probed = System.nanoTime(); probe(candidate); schedule(); }
            });
        } catch (RuntimeException failure) { failRebind(failure); }
    }
    private void failRebind(final Throwable failure) {
        Channel rejected = candidate; candidate = null;
        CompletableFuture<Void> result = rebinding; rebinding = null;
        if (rejected != null) rejected.close();
        if (result != null) result.completeExceptionally(failure);
    }
    private void drain() {
        while (!closed() && engine.canRecv()) {
            if (engine.peekSize() > options().maxMessageBytes()) { fail(NetErrorCode.INVALID_MESSAGE, null); return; }
            ByteBuf message = engine.mergeRecv();
            try { dispatch.receive(codec.decode(ByteBufUtil.getBytes(message))); }
            finally { message.release(); }
        }
    }
    private void output(final ByteBuf body) {
        try {
            if (!closed() && channel.isWritable()) {
                transport.send(channel.alloc(), body, packet -> channel.writeAndFlush(new DatagramPacket(packet, remote))
                        .addListener(done -> { if (!done.isSuccess()) closeWithFailure(NetErrorCode.SEND_FAILED, done.cause()); }));
            }
        } finally { body.release(); }
    }
    private void heartbeat() {
        lastHeartbeat = System.nanoTime();
        if (options().transport().paths().enabled()) probe(channel);
        else output(Unpooled.EMPTY_BUFFER);
    }
    private void emit(final ByteBuf packet) {
        if (closed() || !channel.isWritable()) { packet.release(); return; }
        channel.writeAndFlush(new DatagramPacket(packet, remote)).addListener(done -> {
            if (!done.isSuccess()) closeWithFailure(NetErrorCode.SEND_FAILED, done.cause());
        });
    }
    private void probe(final Channel socket) {
        socket.writeAndFlush(new DatagramPacket(transport.control(socket.alloc(), KcpDatagramCodec.PROBE,
                new byte[KcpPathValidator.CHALLENGE_BYTES]), remote));
    }
    private void tick() {
        if (closed()) return;
        long now = System.nanoTime(); KcpTimeouts time = options().timeouts();
        transport.tick(channel.alloc(), now, this::emit);
        if (quiescing) checkQuiescence(now);
        if (candidate != null) {
            if (now >= rebindUntil) failRebind(new java.util.concurrent.TimeoutException("KCP path validation timed out"));
            else if (now - probed >= options().transport().paths().retryInterval().toNanos()) { probed = now; probe(candidate); }
        }
        if (!info.ticket().expiresAt().isAfter(Instant.now())) { fail(NetErrorCode.AUTHENTICATION_EXPIRED, null); return; }
        if (state() == State.CONNECTING && now - started >= time.bindTimeout().toNanos()
                || now - lastSeen >= time.idleTimeout().toNanos()
                || engine.waitSnd() > 0 && now - progress >= time.progressTimeout().toNanos()) {
            fail(NetErrorCode.HEARTBEAT_TIMEOUT, null); return;
        }
        if (now - lastHeartbeat >= heartbeatNanos()) heartbeat();
        if (engine.waitSnd() > 0) engine.update(millis());
        schedule();
    }
    private long heartbeatNanos() {
        return state() == State.CONNECTING ? Math.min(250_000_000L, options().timeouts().heartbeatInterval().toNanos())
                : options().timeouts().heartbeatInterval().toNanos();
    }
    private void schedule() {
        if (closed()) return;
        long now = System.nanoTime(); long next = Math.min(now + 250_000_000L, lastHeartbeat + heartbeatNanos());
        next = Math.min(next, lastSeen + options().idleTimeout().toNanos());
        if (state() == State.CONNECTING) next = Math.min(next, started + options().timeouts().bindTimeout().toNanos());
        if (engine.waitSnd() > 0) {
            next = Math.min(next, progress + options().timeouts().progressTimeout().toNanos());
            next = Math.min(next, now + Math.max(1, engine.check(millis()) - millis()) * 1_000_000L);
        }
        next = Math.min(next, transport.deadline());
        if (quiescing && !quiesced.isDone()) next = Math.min(next, Math.min(quiesceUntil, now + 10_000_000L));
        if (candidate != null) next = Math.min(next, Math.min(rebindUntil, probed + options().transport().paths().retryInterval().toNanos()));
        if (timer != null && armed <= next) return;
        if (timer != null) timer.cancel(false);
        armed = next;
        timer = channel.eventLoop().schedule(() -> {
            timer = null;
            try { tick(); } catch (RuntimeException failure) { fail(NetErrorCode.SEND_FAILED, failure); }
        }, Math.max(1, next - now), TimeUnit.NANOSECONDS);
    }
    /** @return 本地关闭屏障，不等待业务事务；线程安全、幂等。 */
    @Override public synchronized CompletionStage<Void> close() {
        state.set(State.CLOSED);
        ready.completeExceptionally(KcpServer.error(NetErrorCode.SEND_FAILED, null));
        Channel socket = channel;
        if (socket == null) shutdown();
        else if (socket.eventLoop().inEventLoop()) shutdown();
        else {
            try { socket.eventLoop().execute(this::shutdown); }
            catch (RuntimeException failure) { stopped.completeExceptionally(failure); }
        }
        return stopped.minimalCompletionStage();
    }
    private void shutdown() {
        state.set(State.CLOSED);
        if (timer != null) timer.cancel(false);
        if (engine != null) { engine.release(); engine = null; }
        if (rebinding != null) failRebind(KcpServer.error(NetErrorCode.SEND_FAILED, null));
        transport.close();
        dispatch.close();
        if (quiesced != null) quiesced.completeExceptionally(KcpServer.error(NetErrorCode.SEND_FAILED, null));
        Channel socket = channel;
        if (socket != null) socket.close().addListener(done -> {
            if (borrowed == null && resources != null) resources.close();
            if (done.isSuccess()) stopped.complete(null); else stopped.completeExceptionally(done.cause());
        });
        else { if (borrowed == null && resources != null) resources.close(); stopped.complete(null); }
    }
    private void fail(final NetErrorCode code, final Throwable cause) {
        var failure = KcpServer.error(code, cause);
        ready.completeExceptionally(failure);
        try { listener.onException(this, failure); }
        catch (RuntimeException observer) {
            observer.addSuppressed(failure);
            System.getLogger(KcpClient.class.getName()).log(System.Logger.Level.ERROR, NetErrorCode.OBSERVER_FAILED.code(), observer);
        } finally { close(); }
    }
    /**
     * 暂停新业务发送，等待本地 ACK、已接收 handler 及服务端排空确认；线程安全，不重放业务。
     * 完成后可在 TLS 控制面调用源 migrate、目标 claimConnectInfo，然后关闭本对象并新建客户端。
     * @param timeout 双端排空上限，1ms..30s。 @return 同一暂停信号；超时保持暂停，应用应重新登录。
     * 此屏障不是跨节点业务事务/幂等证明；调用方应先等待业务层期望的响应。
     */
    public synchronized CompletionStage<Void> quiesce(final java.time.Duration timeout) {
        if (timeout == null || timeout.toMillis() < 1 || timeout.compareTo(java.time.Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("invalid quiesce timeout");
        }
        if (quiesced != null) return quiesced.minimalCompletionStage();
        if (state() != State.CONNECTED) return rejected(NetErrorCode.INVALID_LIFECYCLE_STATE);
        quiesced = new CompletableFuture<>(); quiesceUntil = System.nanoTime() + timeout.toNanos(); quiescing = true;
        try { channel.eventLoop().execute(() -> { if (rebinding != null) failRebind(KcpServer.error(NetErrorCode.INVALID_LIFECYCLE_STATE, null)); schedule(); }); }
        catch (RuntimeException failure) { quiesced.completeExceptionally(failure); }
        return quiesced.minimalCompletionStage();
    }
    private void checkQuiescence(final long now) {
        if (quiesced.isDone()) return;
        if (now >= quiesceUntil) { quiesced.completeExceptionally(new java.util.concurrent.TimeoutException("KCP quiesce timed out")); return; }
        if (pending.get() != 0 || !dispatch.idle() || transport.sending()) return;
        if (peerDrained) { quiesced.complete(null); return; }
        if (now - quiesceProbe >= 100_000_000L) {
            quiesceProbe = now; emit(transport.control(channel.alloc(), KcpDatagramCodec.QUIESCE, new byte[0]));
        }
    }
    private long millis() { return epoch + (System.nanoTime() - origin) / 1_000_000; }
    private static CompletionStage<Void> rejected(final NetErrorCode code) { return CompletableFuture.failedFuture(KcpServer.error(code, null)); }
    @Override public boolean reserveInbound(final long bytes) { return KcpBudget.reserve(inbound, options().maxInboundBytesTotal(), bytes); }
    @Override public void releaseInbound(final long bytes) { inbound.addAndGet(-bytes); }
    @Override public Executor executor() { return executor; }
    @Override public boolean inIoThread() { return channel != null && channel.eventLoop().inEventLoop(); }
    @Override public IConnection connection() { return this; }
    @Override public KcpOptions options() { return info.options(); }
    @Override public boolean authorized() { return state() == State.CONNECTED && info.ticket().expiresAt().isAfter(Instant.now()); }
    @Override public boolean closed() { return state() == State.CLOSED; }
    @Override public ConnectionListener listener() { return listener; }
    @Override public ServerFrameHandler handler() { return handler; }
    @Override public void closeWithFailure(final NetErrorCode code, final Throwable cause) {
        Channel socket = channel;
        if (socket == null) fail(code, cause);
        else try { socket.eventLoop().execute(() -> fail(code, cause)); }
        catch (RuntimeException failure) { ready.completeExceptionally(failure); stopped.completeExceptionally(failure); }
    }
    /** 单 socket 入站处理；算法错误关闭本连接。 @author zn */
    private final class Inbound extends SimpleChannelInboundHandler<DatagramPacket> {
        @Override protected void channelRead0(final ChannelHandlerContext context, final DatagramPacket packet) { input(context.channel(), packet); }
        @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable failure) { fail(NetErrorCode.INVALID_MESSAGE, failure); }
        @Override public void channelInactive(final ChannelHandlerContext context) { if (!closed() && context.channel() == channel) fail(NetErrorCode.SEND_FAILED, null); }
    }
}
