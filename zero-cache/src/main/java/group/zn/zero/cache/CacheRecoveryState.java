package group.zn.zero.cache;

/** 后端成功证据与外部故障凭据分域管理；所有操作为短锁，不执行外部 IO。 @author zn */
final class CacheRecoveryState {
    /** 后端最近失败代际。 */
    private long revision;
    /** 已由之后开始的后端成功操作确认的代际。 */
    private long confirmedRevision;
    /** 未被确认的最新外部写回故障凭据，0 表示没有。 */
    private long externalTicket;

    synchronized long begin() { return revision; }
    synchronized void failure() { revision++; }
    synchronized void success(final long observed) {
        if (observed == revision) confirmedRevision = observed;
    }
    synchronized void externalFailure(final long ticket) { externalTicket = ticket; }
    synchronized boolean confirmExternal(final long ticket) {
        if (ticket <= 0 || ticket != externalTicket) return false;
        externalTicket = 0;
        return true;
    }
    synchronized boolean degraded(final long backlog, final boolean cooling) {
        return revision != confirmedRevision || externalTicket != 0 || backlog > 0 || cooling;
    }
}
