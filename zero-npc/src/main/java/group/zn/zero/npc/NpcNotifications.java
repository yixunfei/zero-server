package group.zn.zero.npc;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;

/** 单条命令的观察者失败聚合；继续通知且不修改外部异常对象。 @author zn */
final class NpcNotifications {
    /** 本命令拥有的失败包装。 */
    private ZeroException failure;
    void attempt(final Runnable notification) {
        try { notification.run(); }
        catch (RuntimeException | Error cause) { record(cause); }
    }
    void record(final Throwable cause) {
        var owned = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "NPC command callback failed", cause);
        if (failure == null) failure = owned;
        else failure.addSuppressed(owned);
    }
    void finish() { if (failure != null) throw failure; }
}
