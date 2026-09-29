package group.zn.zero.net.kcp;

import group.zn.zero.net.error.NetErrorCode;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

/** 有限期冻结/排空/提交状态机；仅会话 EventLoop 使用，失败关闭且禁止自动重放。 @author zn */
final class KcpMigration {
    /** 源会话。 */
    private final KcpSession session;
    /** 目标节点。 */
    private final String target;
    /** 排空的单调期限。 */
    private final long until;
    /** 独立结果。 */
    private final CompletableFuture<KcpHandoff> result = new CompletableFuture<>();

    KcpMigration(final KcpSession session, final String target, final Duration timeout) {
        KcpSessionOwner.requireIdentity(session.ticket.conv(), target, session.ticket.generation());
        if (target.equals(session.owner.sessionServices().nodeId()) || session.ticket.generation() == Long.MAX_VALUE
                || timeout == null || timeout.toMillis() < 1 || timeout.compareTo(Duration.ofSeconds(30)) > 0) {
            throw new IllegalArgumentException("invalid KCP migration target/deadline");
        }
        this.session = session; this.target = target; until = System.nanoTime() + timeout.toNanos();
    }
    CompletionStage<KcpHandoff> start() {
        session.freeze(); var services = session.owner.sessionServices();
        KcpStoreCalls.call(session.owner, () -> services.store().freeze(session.ticket.conv(), services.nodeId(), session.ticket.generation()))
                .whenComplete((ok, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(ok)) fail(failure);
                    else { session.owner.counters().reason("migration.frozen"); poll(); }
                });
        return result;
    }
    private void poll() {
        if (session.closed() || !session.authorized() || System.nanoTime() >= until) { fail(null); return; }
        if (session.drained()) { commit(); return; }
        session.owner.channel().eventLoop().schedule(this::poll, 10, TimeUnit.MILLISECONDS);
    }
    private void commit() {
        KcpSessionSnapshot snapshot = session.migrationSnapshot();
        session.lease.fence(); var services = session.owner.sessionServices();
        KcpStoreCalls.call(session.owner, () -> services.store().migrate(snapshot, target, snapshot.generation() + 1, services.lease()))
                .whenComplete((ok, failure) -> {
                    if (failure != null || !Boolean.TRUE.equals(ok)) { fail(failure); return; }
                    session.owner.counters().reason("migration.committed");
                    session.owner.closeSession(session).whenComplete((ignored, closeFailure) -> {
                        if (closeFailure != null) result.completeExceptionally(closeFailure);
                        else result.complete(new KcpHandoff(snapshot.conv(), snapshot.generation() + 1, target, snapshot.expiresAt()));
                    });
                });
    }
    private void fail(final Throwable failure) {
        if (result.isDone()) return;
        session.owner.counters().reason("migration.failed");
        session.owner.fail(session, NetErrorCode.AUTHORIZATION_DENIED, failure);
        result.completeExceptionally(KcpServer.error(NetErrorCode.AUTHORIZATION_DENIED, failure));
    }
}
