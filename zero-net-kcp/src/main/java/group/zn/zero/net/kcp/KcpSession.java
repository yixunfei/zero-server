package group.zn.zero.net.kcp;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.protocol.ProtocolFrame;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.concurrent.CompletableFuture;
import kcp.Kcp;

/** 单 EventLoop 拥有的 KCP 会话及算法状态；不允许直接跨线程调用。 @author zn */
final class KcpSession implements KcpDispatchEndpoint {
    /** 所属服务器。 */
    final KcpServer owner;
    /** 授权凭证。 */
    final KcpTicket ticket;
    /** 已认证的 TCP 控制连接。 */
    final IConnection control;
    /** 授权主体快照。 */
    final String subject;
    /** 登录代际快照，重新认证后旧票据立即失效。 */
    final group.zn.zero.security.SecurityContext identity;
    /** 单端点认证与重放状态。 */
    private final KcpTransport transport;
    /** 已验证路径和候选限额。 */
    private final KcpPathValidator paths;
    /** 延迟创建的算法；只有完成认证与包验证才创建。 */
    private Kcp engine;
    /** 首次通过认证后固定的 UDP 端点。 */
    volatile InetSocketAddress remote;
    /** 公开的线程安全句柄。 */
    final KcpConnection connection;
    /** 发送预算。 */
    final KcpBudget budget;
    /** 异步租约和本地 fencing。 */
    final KcpLease lease;
    /** 已冻结的新业务入站；单 EventLoop 写入。 */
    private boolean frozen;
    /** 客户端已确认自己的发送队列排空，禁止继续接受新 PUSH。 */
    private boolean peerQuiescing;
    /** 串行业务队列。 */
    private final KcpDispatch dispatch;
    /** 最近有效包时间，单调时钟。 */
    private long lastSeen = System.nanoTime();
    /** 第一条未排空发送的时间，避免无 ACK 但持续心跳无限占用。 */
    private long pendingSince;
    /** 算法已接收但未排空的预算；提交队列预算由各任务单独持有。 */
    private long charged;
    /** 关闭幂等状态。 */
    private boolean closed;
    /** 将单调时钟映射到上游所需的 epoch 毫秒，避免系统时钟跳变影响重传。 */
    private final long epochOrigin = System.currentTimeMillis();
    /** 对应的单调时钟原点。 */
    private final long nanoOrigin = System.nanoTime();

    KcpSession(final KcpServer owner, final KcpTicket ticket, final IConnection control,
            final group.zn.zero.security.SecurityContext identity) {
        this.owner = owner;
        this.ticket = ticket;
        this.control = control;
        this.subject = identity.subject();
        this.identity = identity;
        transport = new KcpTransport(ticket, true, owner.config(), owner.algorithms(),
                owner::reserveFec, owner::releaseFec, owner.counters()::reason);
        paths = new KcpPathValidator(owner.config().transport().paths());
        connection = new KcpConnection(this);
        connection.attributes().put(ProductionNetworkConnectionAttributes.SUBJECT_ID, subject);
        budget = owner.newBudget();
        dispatch = new KcpDispatch(this);
        lease = new KcpLease(this);
    }
    void input(final DatagramPacket packet) {
        if (closed) return;
        ByteBuf body = transport.decode(packet.content());
        if (body == null) { owner.rejected(); return; }
        try {
            int type = transport.type();
            if (!route(packet, type, body) || closed) return;
            lastSeen = System.nanoTime();
            if (type == KcpDatagramCodec.QUIESCE && !body.isReadable()) {
                peerQuiescing = true;
                if (drained()) emit(transport.control(owner.channel().alloc(), KcpDatagramCodec.QUIESCED, new byte[0]));
                return;
            }
            if (type == KcpDatagramCodec.PROBE) { output(Unpooled.EMPTY_BUFFER); return; }
            if (type != KcpDatagramCodec.RESPONSE) transport.input(type, body, this::inputBody);
        } finally { body.release(); }
    }
    private boolean route(final DatagramPacket packet, final int type, final ByteBuf body) {
        InetSocketAddress sender = packet.sender(); long now = System.nanoTime();
        if (remote == null) {
            if (!(control.remoteAddress() instanceof InetSocketAddress tcp) || !tcp.getAddress().equals(sender.getAddress())
                    || type != KcpDatagramCodec.DATA && type != KcpDatagramCodec.PROBE && type != KcpDatagramCodec.FEC) {
                owner.rejected("address"); return false;
            }
            if (type == KcpDatagramCodec.DATA && body.isReadable() && !KcpSegments.valid(body, ticket.conv(), owner.config())) {
                owner.fail(this, NetErrorCode.INVALID_MESSAGE, null); return false;
            }
            bind(sender); return true;
        }
        if (paths.accepts(sender, now)) return true;
        if (type == KcpDatagramCodec.RESPONSE && paths.confirm(sender, ByteBufUtil.getBytes(body), now)) {
            remote = paths.active(); owner.counters().reason("path.migrated"); output(Unpooled.EMPTY_BUFFER); return true;
        }
        int responseBytes = KcpDatagramCodec.HEADER_BYTES
                + owner.algorithms().protection(owner.config().transport().protectionId()).tagBytes() + KcpPathValidator.CHALLENGE_BYTES;
        byte[] token = paths.challenge(sender, now, packet.content().readableBytes(), responseBytes);
        if (token != null) {
            owner.write(new DatagramPacket(transport.control(owner.channel().alloc(), KcpDatagramCodec.CHALLENGE, token), sender), this);
            owner.counters().reason("path.challenge");
        }
        owner.rejected("unvalidated_path"); return false;
    }
    private void inputBody(final ByteBuf body) {
        if (closed) return;
        if (!body.isReadable()) { output(Unpooled.EMPTY_BUFFER); return; }
        if (!KcpSegments.valid(body, ticket.conv(), owner.config())) { owner.fail(this, NetErrorCode.INVALID_MESSAGE, null); return; }
        if ((frozen || peerQuiescing) && KcpSegments.hasPush(body)) { owner.rejected("migration_frozen"); return; }
        int waiting = engine.waitSnd();
        if (engine.input(body, true, nowMillis()) != 0) { owner.fail(this, NetErrorCode.INVALID_MESSAGE, null); return; }
        engine.flush(true, nowMillis()); drain(); releaseAcknowledged(waiting);
    }
    private void emit(final ByteBuf packet) {
        if (closed || remote == null || !owner.channel().isWritable()) { packet.release(); owner.rejected("socket_backpressure"); }
        else owner.write(new DatagramPacket(packet, remote), this);
    }
    private void bind(final InetSocketAddress sender) {
        remote = sender; paths.bind(sender);
        owner.counters().connected.incrementAndGet();
        engine = new Kcp(ticket.conv(), (bytes, ignored) -> output(bytes));
        owner.config().tuning().apply(engine);
        engine.update(nowMillis());
        connection.attributes().put(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT,
                new group.zn.zero.security.SecurityContext(identity.subject(), identity.authenticatedAt(),
                        identity.expiresAt(), "kcp", sender.toString(), identity.trustedSourceAddress(),
                        identity.traceId(), identity.correlationId(), identity.permissions(), identity.attributes()));
        dispatch.open();
    }
    void send(final ProtocolFrame frame, final long charge, final CompletableFuture<Void> result) {
        boolean transferred = false;
        long reserved = charge;
        try {
            if (closed || engine == null || !authorized()) {
                throw ZeroException.of(NetErrorCode.SEND_FAILED, "KCP closed or authorization expired", null);
            }
            byte[] bytes = owner.codec().encode(frame);
            if (bytes.length == 0 || bytes.length > owner.maxFrame()) {
                throw ZeroException.of(NetErrorCode.INVALID_MESSAGE, "KCP frame too large", null);
            }
            int segments = (bytes.length + owner.config().mtu() - 25) / (owner.config().mtu() - 24);
            if (engine.waitSnd() + segments > owner.config().maxPendingSegments()) {
                throw ZeroException.of(NetErrorCode.OUTBOUND_OVERFLOW, "KCP segment budget exhausted", null);
            }
            long retained = segments * (owner.config().mtu() + 128L);
            if (retained > charge) throw KcpServer.error(NetErrorCode.INVALID_MESSAGE, null);
            ByteBuf source = new KcpRetainedFrame(owner.channel().alloc(), bytes, () -> released(retained));
            owner.counters().reason("send.frame");
            budget.release(charge - retained);
            reserved = retained;
            if (charged == 0) pendingSince = System.nanoTime();
            charged += retained;
            transferred = true;
            try {
                if (engine.send(source) != 0) throw ZeroException.of(NetErrorCode.SEND_FAILED, "KCP send rejected", null);
            } finally { source.release(); }
            engine.flush(false, nowMillis());
            owner.schedule(this);
            result.complete(null);
        } catch (RuntimeException failure) {
            if (!transferred) budget.release(reserved);
            else owner.closeWithFailure(this, NetErrorCode.SEND_FAILED, failure);
            result.completeExceptionally(failure instanceof ZeroException ? failure
                    : ZeroException.of(NetErrorCode.SEND_FAILED, "KCP encode failed", failure));
        }
    }
    void tick(final long now) {
        if (closed) return;
        if (lease.fenced()) return;
        lease.tick(now);
        if (closed) return;
        transport.tick(owner.channel().alloc(), now, this::emit);
        if (paths.expire(now) > 0) owner.counters().reason("path.expired");
        if (!authorized()) { owner.fail(this, NetErrorCode.AUTHENTICATION_EXPIRED, null); return; }
        long lifetime = remote == null ? owner.config().timeouts().bindTimeout().toNanos()
                : owner.config().idleTimeout().toNanos();
        if (now - lastSeen >= lifetime
                || charged > 0 && now - pendingSince >= owner.config().timeouts().progressTimeout().toNanos()) {
            owner.fail(this, NetErrorCode.HEARTBEAT_TIMEOUT, null);
            return;
        }
        if (engine != null && engine.waitSnd() > 0) {
            owner.counters().updates.incrementAndGet();
            engine.update(nowMillis());
            if (engine.getState() == -1) owner.fail(this, NetErrorCode.SEND_FAILED, null);
        }
    }
    void close() {
        if (closed) return;
        closed = true;
        lease.close();
        connection.markClosed();
        if (remote != null) owner.counters().connected.decrementAndGet();
        try { if (engine != null) engine.release(); }
        finally {
            engine = null;
            transport.close(); dispatch.close();
        }
    }
    private void drain() {
        while (!closed && engine.canRecv()) {
            if (engine.peekSize() > owner.maxFrame()) {
                owner.fail(this, NetErrorCode.INVALID_MESSAGE, null);
                return;
            }
            ByteBuf message = engine.mergeRecv();
            try { dispatch.receive(owner.codec().decode(ByteBufUtil.getBytes(message))); }
            finally { message.release(); }
        }
    }
    private void releaseAcknowledged(final int previousWaiting) {
        if (closed) return;
        int remaining = engine.waitSnd();
        if (remaining < previousWaiting) pendingSince = System.nanoTime();
        // 内存预算由每个整帧 ByteBuf 的最后一次 release 回收，不能按 ACK 数量推算。
    }
    private void released(final long amount) { charged -= amount; budget.release(amount); }
    long nextDeadline(final long now) {
        if (lease.fenced()) return now + owner.sessionServices().operationTimeout().toNanos();
        long lifetime = remote == null ? owner.config().timeouts().bindTimeout().toNanos()
                : owner.config().idleTimeout().toNanos();
        long next = Math.min(now + 250_000_000L, lastSeen + lifetime);
        if (charged > 0) next = Math.min(next, pendingSince + owner.config().timeouts().progressTimeout().toNanos());
        if (engine != null && engine.waitSnd() > 0) {
            long delay = Math.max(1, engine.check(nowMillis()) - nowMillis());
            next = Math.min(next, now + delay * 1_000_000L);
        }
        return Math.min(Math.min(next, transport.deadline()), lease.deadline());
    }
    private void output(final ByteBuf body) {
        try {
            if (closed || !lease.valid() || !owner.channel().isWritable()) { owner.rejected("socket_backpressure"); return; }
            transport.send(owner.channel().alloc(), body, this::emit);
        } finally { body.release(); }
    }
    @Override public boolean authorized() {
        return authorized(java.time.Instant.now());
    }
    boolean authorized(final java.time.Instant now) {
        return lease.valid() && ticket.expiresAt().isAfter(now) && !identity.expired(now)
                && control.attributes().get(ProductionNetworkConnectionAttributes.STATE)
                        .orElse(group.zn.zero.net.lifecycle.ConnectionLifecycleState.CLOSED)
                        == group.zn.zero.net.lifecycle.ConnectionLifecycleState.ESTABLISHED
                && control.attributes().get(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT)
                        .map(value -> value == identity).orElse(false)
                && control.attributes().get(ProductionNetworkConnectionAttributes.SUBJECT_ID)
                        .map(subject::equals).orElse(false);
    }
    private long nowMillis() { return epochOrigin + (System.nanoTime() - nanoOrigin) / 1_000_000L; }
    void freeze() { frozen = true; }
    boolean frozen() { return frozen; }
    boolean peerQuiescing() { return peerQuiescing; }
    boolean drained() { return dispatch.idle() && budget.pending() == 0 && !transport.sending(); }
    KcpSessionSnapshot migrationSnapshot() {
        return new KcpSessionSnapshot(ticket.conv(), ticket.generation(), owner.sessionServices().nodeId(), subject,
                remote, ticket.expiresAt(), transport.receiveWindow(), transport.sentSequence(), transport.receivedSequence());
    }
    @Override public boolean reserveInbound(final long bytes) { return owner.reserveInbound(bytes); }
    @Override public void releaseInbound(final long bytes) { owner.releaseInbound(bytes); }
    @Override public java.util.concurrent.Executor executor() { return owner.executor(); }
    @Override public boolean inIoThread() { return owner.channel().eventLoop().inEventLoop(); }
    @Override public IConnection connection() { return connection; }
    @Override public KcpOptions options() { return owner.config(); }
    @Override public boolean closed() { return connection.isClosed(); }
    @Override public group.zn.zero.net.ConnectionListener listener() { return owner.listener(); }
    @Override public group.zn.zero.net.ServerFrameHandler handler() { return owner.handler(); }
    @Override public void closeWithFailure(final NetErrorCode code, final Throwable cause) {
        owner.closeWithFailure(this, code, cause);
    }
}
