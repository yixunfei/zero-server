package group.zn.zero.net.kcp;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.security.SecurityContext;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/** 有界授权表；仅 EventLoop 访问，票据与已绑定连接共享同一容量。 @author zn */
final class KcpSessions {
    /** 所属服务。 */
    private final KcpServer owner;
    /** 绝对过期时钟。 */
    private final Clock clock;
    /** conv 到授权会话的唯一映射。 */
    private final Map<Integer, KcpSession> entries = new HashMap<>();
    /** 每个 TCP 控制连接至多一份有效票据。 */
    private final Map<String, Integer> controls = new HashMap<>();
    /** 初始化时创建，生成不可预测 conv 与密钥。 */
    private final SecureRandom random = new SecureRandom();
    /** 有界截止调度器。 */
    private final KcpScheduler scheduler;

    KcpSessions(final KcpServer owner, final Clock clock) {
        this.owner = owner;
        this.clock = clock;
        scheduler = new KcpScheduler(owner);
    }
    java.util.concurrent.CompletionStage<KcpTicket> issue(final IConnection control) {
        SecurityContext context = authenticate(control);
        Instant now = clock.instant();
        Integer previous = controls.get(control.connectionId());
        if (previous == null && entries.size() >= owner.config().maxSessions()) {
            throw KcpServer.error(NetErrorCode.RATE_LIMITED, null);
        }
        Instant expires = now.plus(owner.config().ticketLifetime());
        if (expires.isAfter(context.expiresAt())) expires = context.expiresAt();
        int conv;
        do { conv = random.nextInt(); } while (conv == 0 || entries.containsKey(conv));
        return register(control, context, ticket(conv, expires, 1), false);
    }
    private SecurityContext authenticate(final IConnection control) {
        String subject = subject(control);
        Instant now = clock.instant();
        SecurityContext context = control.attributes().get(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT)
                .orElseThrow(() -> KcpServer.error(NetErrorCode.UNAUTHENTICATED, null));
        if (context.expired(now) || !context.subject().equals(subject)) {
            throw KcpServer.error(NetErrorCode.AUTHENTICATION_EXPIRED, null);
        }
        if (owner.config().requireControlTls() && !control.attributes()
                .get(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED).orElse(false)) {
            throw KcpServer.error(NetErrorCode.TLS_REQUIRED, null);
        }
        if (!(control.remoteAddress() instanceof InetSocketAddress address) || address.isUnresolved()) {
            throw KcpServer.error(NetErrorCode.AUTHENTICATION_REJECTED, null);
        }
        return context;
    }
    private KcpTicket ticket(final int conv, final Instant expires, final long generation) {
        byte[] key = new byte[32];
        random.nextBytes(key);
        try { return new KcpTicket(conv, key, expires, generation); }
        finally { java.util.Arrays.fill(key, (byte) 0); }
    }
    private java.util.concurrent.CompletionStage<KcpTicket> register(final IConnection control, final SecurityContext context,
            final KcpTicket ticket, final boolean claim) {
        if (!owner.channel().isActive()) throw KcpServer.error(NetErrorCode.INVALID_LIFECYCLE_STATE, null);
        Integer previous = controls.get(control.connectionId()); int conv = ticket.conv();
        if (entries.containsKey(conv) || previous == null && entries.size() >= owner.config().maxSessions()) {
            throw KcpServer.error(NetErrorCode.RATE_LIMITED, null);
        }
        KcpSession session = new KcpSession(owner, ticket, control, context);
        if (previous != null) remove(previous, "replaced");
        entries.put(conv, session);
        controls.put(control.connectionId(), conv);
        owner.counters().sessions.incrementAndGet();
        return activate(session, claim);
    }
    java.util.concurrent.CompletionStage<KcpTicket> claim(final IConnection control, final KcpHandoff handoff) {
        authenticate(control);
        if (!owner.sessionServices().nodeId().equals(handoff.targetNode())) throw KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, null);
        return KcpStoreCalls.call(owner, () -> owner.sessionServices().store().load(handoff.conv())).thenCompose(snapshot -> {
            SecurityContext identity = authenticate(control);
            if (snapshot == null || !snapshot.subject().equals(identity.subject())
                    || snapshot.generation() == Long.MAX_VALUE || snapshot.generation() + 1 != handoff.generation()
                    || !snapshot.expiresAt().isAfter(clock.instant())) throw KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, null);
            Instant expires = snapshot.expiresAt().isBefore(identity.expiresAt()) ? snapshot.expiresAt() : identity.expiresAt();
            return register(control, identity, ticket(handoff.conv(), expires, handoff.generation()), true);
        });
    }
    java.util.concurrent.CompletionStage<KcpHandoff> migrate(final IConnection control, final int conv,
            final String target, final java.time.Duration timeout) {
        authenticate(control); KcpSession session = entries.get(conv);
        if (session == null || session.control != control || !session.authorized() || session.remote == null
                || session.frozen() || !session.peerQuiescing()) {
            throw KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, null);
        }
        return new KcpMigration(session, target, timeout).start();
    }
    private java.util.concurrent.CompletionStage<KcpTicket> activate(final KcpSession session, final boolean claim) {
        var result = new java.util.concurrent.CompletableFuture<KcpTicket>();
        session.lease.start(claim).whenComplete((ok, failure) -> {
            if (failure != null || !Boolean.TRUE.equals(ok) || !owner.channel().isActive() || !session.authorized()) {
                removeCurrent(session, "ownership_rejected");
                result.completeExceptionally(KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, failure));
            } else { scheduler.schedule(session); result.complete(session.ticket); }
        });
        return result;
    }
    void input(final DatagramPacket packet) {
        KcpSession session = entries.get(KcpDatagramCodec.conversation(packet.content()));
        if (session == null) { owner.rejected("unknown_session"); return; }
        if (session.lease.fenced()) { owner.rejected("migration_committed"); return; }
        if (!valid(session)) { owner.fail(session, NetErrorCode.AUTHENTICATION_EXPIRED, null); return; }
        try { session.input(packet); scheduler.schedule(session); }
        catch (RuntimeException failure) { owner.fail(session, NetErrorCode.INVALID_MESSAGE, failure); }
    }
    void schedule(final KcpSession session) { if (!session.lease.fenced()) scheduler.schedule(session); }
    void remove(final int conv, final String reason) {
        KcpSession removed = entries.remove(conv);
        if (removed == null) return;
        owner.counters().reason("close." + reason);
        scheduler.remove(removed);
        owner.counters().sessions.decrementAndGet();
        controls.remove(removed.control.connectionId(), conv);
        removed.close();
    }
    void removeCurrent(final KcpSession session) {
        removeCurrent(session, "local");
    }
    void removeCurrent(final KcpSession session, final String reason) {
        if (entries.get(session.ticket.conv()) == session) remove(session.ticket.conv(), reason);
    }
    void fallback(final IConnection control, final int conv) {
        subject(control);
        KcpSession session = entries.get(conv);
        if (session != null && session.control != control) {
            throw KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, null);
        }
        remove(conv, "tcp_fallback");
    }
    void close() {
        scheduler.close();
        for (KcpSession session : entries.values()) { owner.counters().reason("close.service_stop"); session.close(); }
        entries.clear();
        controls.clear();
        owner.counters().sessions.set(0);
    }
    private boolean valid(final KcpSession session) {
        return session.authorized(clock.instant());
    }
    private static String subject(final IConnection control) {
        if (control == null || control.serverType() != ServerType.TCP
                || control.attributes().get(ProductionNetworkConnectionAttributes.STATE)
                        .orElse(ConnectionLifecycleState.CLOSED) != ConnectionLifecycleState.ESTABLISHED) {
            throw KcpServer.error(NetErrorCode.UNAUTHENTICATED, null);
        }
        return control.attributes().get(ProductionNetworkConnectionAttributes.SUBJECT_ID)
                .filter(value -> !value.isBlank())
                .orElseThrow(() -> KcpServer.error(NetErrorCode.UNAUTHENTICATED, null));
    }
}
