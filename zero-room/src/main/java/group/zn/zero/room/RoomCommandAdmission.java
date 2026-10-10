package group.zn.zero.room;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** 创建期间的 FIFO 准入屏障；只串行转交 scheduler，不等待命令完成，不持锁调用用户代码。 @author zn */
final class RoomCommandAdmission {
    /** 冷创建及 direct 回调转交窗口的独立上限，不能绕过受管 scheduler 无限积压。 */
    static final int CAPACITY = 4096;
    /** 等待转交的任务，实例锁保护。 */
    private final ArrayDeque<Submission> pending = new ArrayDeque<>();
    /** 创建通知已结束。 */
    private boolean started;
    /** 唯一转交者正在推进；包括 direct scheduler 重入。 */
    private boolean draining;

    /** 开放已提交房间，不因 CREATED 消费者失败回滚。 */
    void start() {
        boolean run;
        synchronized (this) { started = true; run = claim(); }
        if (run) drain();
    }

    /** 线程安全地保存提交顺序，返回只读完成信号。 */
    CompletionStage<Void> submit(final Supplier<CompletionStage<Void>> action) {
        var result = new CompletableFuture<Void>();
        boolean run;
        synchronized (this) {
            if (pending.size() >= CAPACITY) return CompletableFuture.failedFuture(
                    group.zn.zero.core.error.ZeroException.of(
                            group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR,
                            "room command admission capacity exceeded", null));
            pending.addLast(new Submission(action, result));
            run = claim();
        }
        if (run) drain();
        return result.minimalCompletionStage();
    }

    private boolean claim() {
        if (!started || draining) return false;
        draining = true;
        return true;
    }

    private void drain() {
        while (true) {
            Submission submission;
            synchronized (this) {
                submission = pending.pollFirst();
                if (submission == null) { draining = false; return; }
            }
            try {
                submission.action().get().whenComplete((ignored, failure) -> {
                    if (failure == null) submission.result().complete(null);
                    else submission.result().completeExceptionally(failure);
                });
            } catch (RuntimeException | Error failure) { submission.result().completeExceptionally(failure); }
        }
    }

    /** 单次转交操作及其调用方完成信号。 @author zn */
    private record Submission(Supplier<CompletionStage<Void>> action, CompletableFuture<Void> result) { }
}
