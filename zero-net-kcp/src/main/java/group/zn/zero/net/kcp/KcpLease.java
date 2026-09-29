package group.zn.zero.net.kcp;

import group.zn.zero.net.error.NetErrorCode;
import java.util.concurrent.CompletionStage;

/** 单会话租约；操作从请求前的单调时间计期，超时/冲突立即关闭，永不延长失联 owner。 @author zn */
final class KcpLease {
    /** 所属单执行域会话。 */
    private final KcpSession session;
    /** 外部存储资源。 */
    private final KcpSessionServices services;
    /** 跨业务执行域可见的本地租约期限。 */
    private volatile long expires;
    /** 是否仍允许数据面操作。 */
    private volatile boolean active;
    /** 续租操作排它标记。 */
    private boolean renewing;
    /** 下次续租。 */
    private long nextRenew;
    /** 已释放标记。 */
    private volatile boolean closed;
    /** 仅成功取得的租约可以释放；失败 claim 不能删除获胜节点的记录。 */
    private final java.util.concurrent.atomic.AtomicBoolean acquired = new java.util.concurrent.atomic.AtomicBoolean();
    /** 提交阶段暂停数据面和续租回调，避免迟到完成恢复旧 owner。 */
    private boolean fenced;

    KcpLease(final KcpSession session) { this.session = session; services = session.owner.sessionServices(); }
    CompletionStage<Boolean> start(final boolean claim) {
        long start = System.nanoTime();
        return KcpStoreCalls.call(session.owner, () -> {
            CompletionStage<Boolean> raw = claim
                    ? services.store().claim(conv(), services.nodeId(), generation(), services.lease())
                    : services.store().acquire(conv(), services.nodeId(), generation(), services.lease());
            // 超时后迟到的成功不能遗留活 owner；回调只调用非阻塞 SPI，不触碰算法。
            raw.whenComplete((ok, failure) -> { if (Boolean.TRUE.equals(ok) && closed) release(); });
            return raw.thenApply(ok -> {
                if (Boolean.TRUE.equals(ok)) { acquired.set(true); if (closed) release(); }
                return ok;
            });
        }).thenApply(ok -> {
            if (!Boolean.TRUE.equals(ok) || closed || session.closed()) return false;
            expires = start + services.lease().toNanos();
            nextRenew = start + services.lease().toNanos() / 3;
            active = System.nanoTime() < expires;
            return active;
        });
    }
    boolean valid() { return active && System.nanoTime() < expires; }
    long deadline() { return renewing ? expires : Math.min(expires, nextRenew); }
    void tick(final long now) {
        if (fenced) return;
        if (!valid()) { lost(null); return; }
        if (renewing || now < nextRenew) return;
        renewing = true; long start = System.nanoTime();
        KcpStoreCalls.call(session.owner, () -> services.store().renew(conv(), services.nodeId(), generation(), services.lease()))
                .whenComplete((ok, failure) -> {
                    renewing = false;
                    if (closed || fenced) return;
                    if (failure != null || !Boolean.TRUE.equals(ok) || !valid()) { lost(failure); return; }
                    expires = start + services.lease().toNanos();
                    nextRenew = start + services.lease().toNanos() / 3;
                    session.owner.counters().reason("lease.renewed"); session.owner.schedule(session);
                });
    }
    void fence() { fenced = true; active = false; }
    boolean fenced() { return fenced; }
    void close() { if (!closed) { closed = true; active = false; release(); } }
    private void release() {
        if (!acquired.compareAndSet(true, false)) return;
        try {
            services.store().release(conv(), services.nodeId(), generation()).whenComplete((ok, failure) -> {
                if (failure != null) session.owner.observeFailure(session.connection, NetErrorCode.STOP_FAILED, failure);
            });
        } catch (RuntimeException failure) { session.owner.observeFailure(session.connection, NetErrorCode.STOP_FAILED, failure); }
    }
    private void lost(final Throwable failure) {
        active = false; session.owner.counters().reason("lease.lost");
        session.owner.fail(session, NetErrorCode.AUTHORIZATION_DENIED, failure);
    }
    private int conv() { return session.ticket.conv(); }
    private long generation() { return session.ticket.generation(); }
}
