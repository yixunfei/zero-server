package group.zn.zero.data.persistence.durable;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.envelope.ZeroDataEnvelope;
import group.zn.zero.data.envelope.ZeroDataEnvelopeStore;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.persistence.DataThreadBinding;
import group.zn.zero.data.persistence.PersistenceBindingExecutor;
import group.zn.zero.data.persistence.PersistenceRetryPolicy;
import java.time.Clock;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * 显式耐久提交：绑定域捕获快照 -> IO 域刷盘意图 -> 返回收据 -> flush CAS -> 刷盘处理结果。
 * PENDING 收据确认本地耐久性，不表示后端成功；同键最多一个未解决意图，冲突隔离不强制覆盖。
 * 未提交的内存修改不受保护。操作 ID 及完整快照需稳定，以便重试和核验未知写结果。
 * 不创建线程；所有日志/后端操作在注入的非内联受管 IO executor 上串行执行。
 * @author zn
 */
public final class DurablePersistenceService implements AutoCloseable {
    /** 独占持有的耐久日志。 */
    private final LocalDurableJournal journal;
    /** 冷启动时固定的目标，服务关闭不关闭这些外部存储。 */
    private final Map<String, ZeroDataEnvelopeStore> targets;
    /** 绑定域快照捕获器。 */
    private final PersistenceBindingExecutor bindings;
    /** 受管 IO 执行器；禁止直接执行器。 */
    private final Executor io;
    /** 重试时钟。 */
    private final Clock clock;
    /** 重试预算。 */
    private final PersistenceRetryPolicy retryPolicy;
    /** 准入/关闭锁；不在此锁内执行 IO 或快照捕获。 */
    private final Object admission = new Object();
    /** 被接受但尚未完成的请求；最多 1024，包括等待绑定域捕获的提交。 */
    private final Set<CompletableFuture<?>> accepted = new HashSet<>();
    /** IO 链调度锁。 */
    private final Object serial = new Object();
    /** 串行 IO 尾部。 */
    private CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
    /** 关闭未来，建立后停止新准入。 */
    private CompletableFuture<Void> closing;

    /** 创建服务；已有日志意图由 flushNow 重放，隔离记录保持隔离；线程安全。
     * @param journal 独占日志，服务负责关闭。 @param targets 不可变目标映射。
     * @param bindings 绑定执行器。 @param io 非内联受管 IO 执行器。 @param clock 时钟。
     * @param retryPolicy 重试策略；不可为空。 */
    public DurablePersistenceService(final LocalDurableJournal journal, final Map<String, ZeroDataEnvelopeStore> targets,
            final PersistenceBindingExecutor bindings, final Executor io, final Clock clock,
            final PersistenceRetryPolicy retryPolicy) {
        this.journal = Objects.requireNonNull(journal, "journal"); this.targets = Map.copyOf(targets);
        this.bindings = Objects.requireNonNull(bindings, "bindings"); this.io = Objects.requireNonNull(io, "io");
        this.clock = Objects.requireNonNull(clock, "clock"); this.retryPolicy = Objects.requireNonNull(retryPolicy, "retryPolicy");
    }

    /** 捕获并耐久接受一个 CAS 意图。只有 force 成功后才完成；取消返回 future 不撤销已接受操作。
     * @param operationId 稳定去重 ID，最多 256 字符。 @param target 已注册目标。
     * @param expectedVersion 后端期望版本，0 为仅创建。 @param binding 实体绑定域。
     * @param snapshotSupplier 返回完整不可变信封，version=expectedVersion+1。
     * @return 刷盘后的记录；线程安全，失败异步携带 ErrorCode。 */
    public CompletionStage<DurableRecord> submit(final String operationId, final String target, final long expectedVersion,
            final DataThreadBinding binding, final Supplier<ZeroDataEnvelope> snapshotSupplier) {
        return tracked(() -> {
            requireTarget(target);
            return bindings.capture(binding, snapshotSupplier).thenCompose(snapshot -> enqueue(() -> {
                var intent = new DurableRecord(operationId, target, expectedVersion, snapshot,
                        DurableRecord.State.PENDING, 0, 0, "");
                DurableRecord existing = journal.find(operationId);
                if (existing != null) {
                    if (!existing.sameIntent(intent)) throw failure("operation id conflicts with a prior intent", null);
                    return existing;
                }
                if (journal.hasUnresolved(target, snapshot.id())) throw failure("entity has an unresolved durable intent", null);
                journal.append(intent);
                return intent;
            }));
        });
    }

    /** 重放到期意图；冲突/耗尽预算隔离并保留，未知提交通过完整快照核验。
     * @param limit 单批预算，1..1024。 @return 本批成功数；线程安全，IO 异常通过记录及结果暴露。 */
    public CompletionStage<Integer> flushNow(final int limit) {
        if (limit < 1 || limit > 1024) throw new IllegalArgumentException("limit must be 1..1024");
        return tracked(() -> enqueue(() -> {
            int applied = 0;
            List<DurableRecord> batch = journal.pending(clock.millis(), limit);
            for (DurableRecord record : batch) if (apply(record)) applied++;
            return applied;
        }));
    }

    /** @return 不可变、有序的诊断/收据快照；线程安全，无磁盘 IO。 */
    public List<DurableRecord> records() { return journal.records(); }

    /** 修正后显式恢复隔离/退避状态；不改变意图或绕过 CAS。
     * @param operationId 操作 ID。 @return 恢复后的记录；不存在或已完成时拒绝。 */
    public CompletionStage<DurableRecord> retry(final String operationId) {
        return tracked(() -> enqueue(() -> {
            DurableRecord record = journal.find(operationId);
            if (record == null || record.state() == DurableRecord.State.APPLIED) throw failure("intent is not retryable", null);
            DurableRecord pending = record.result(DurableRecord.State.PENDING, 0, 0, "");
            journal.append(pending); return pending;
        }));
    }

    /** 压缩重复状态记录；保留全部幂等收据，容量耗尽时明确拒绝新意图。
     * @return 刷盘完成信号；线程安全。 */
    public CompletionStage<Void> compact() {
        return tracked(() -> enqueue(() -> { journal.compact(); return null; }));
    }

    /**
     * 在受管 IO 队列确认已归档结果并释放收据，与提交/flush 串行；线程安全。
     * operationId 为 String.compareTo 单调水位，之后旧 ID 明确拒绝；未解决意图禁止回收。
     * @param operationId 包含归档边界。 @return 释放条数的完成信号；失败保留现场。
     */
    public CompletionStage<Integer> acknowledgeThrough(final String operationId) {
        return tracked(() -> enqueue(() -> journal.acknowledgeThrough(operationId)));
    }

    /** 停止准入，等待已接受的捕获/刷盘，然后释放日志锁；待落库意图由下次启动恢复。
     * @return 关闭完成信号；线程安全且幂等，调用者不得在 Actor/IO worker 上同步等待。 */
    public CompletionStage<Void> closeAsync() {
        synchronized (admission) {
            if (closing == null) {
                closing = CompletableFuture.allOf(accepted.toArray(CompletableFuture[]::new))
                        .handle((ignored, failure) -> null).thenRunAsync(journal::close, io);
            }
            return closing.copy();
        }
    }

    /** 生命周期关闭等待至多 30 秒；仅从管理线程调用，失败不宣称已关闭。 */
    @Override public void close() {
        try { closeAsync().toCompletableFuture().get(30, TimeUnit.SECONDS); }
        catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw failure("durable close interrupted", failure); }
        catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException failure) {
            throw failure("durable close did not complete", failure);
        }
    }

    private boolean apply(final DurableRecord record) {
        DurableRecord next;
        try {
            ZeroDataEnvelopeStore store = requireTarget(record.target());
            boolean alreadyApplied = matches(store, record);
            if (!alreadyApplied && !store.saveIfVersion(record.envelope(), record.expectedVersion()) && !matches(store, record)) {
                throw ZeroException.of(DataErrorCode.VERSION_CONFLICT, "durable CAS rejected", null);
            }
            next = record.result(DurableRecord.State.APPLIED, record.attempts() + 1, 0, "");
        } catch (RuntimeException failure) {
            var code = failure instanceof ZeroException zero ? zero.errorCode() : DataErrorCode.WRITE_FAILED;
            int attempts = record.attempts() + 1;
            boolean isolated = attempts >= retryPolicy.maxAttempts() || code == DataErrorCode.VERSION_CONFLICT
                    || code == DataErrorCode.MAPPING_INVALID || code == DataErrorCode.ATOMIC_WRITE_UNSUPPORTED
                    || code == DataErrorCode.PERSISTENCE_TARGET_NOT_FOUND;
            long delay = retryPolicy.delayMillis(attempts);
            long now = Math.max(0, clock.millis());
            long at = now > Long.MAX_VALUE - delay ? Long.MAX_VALUE : now + delay;
            next = record.result(isolated ? DurableRecord.State.QUARANTINED : DurableRecord.State.PENDING,
                    attempts, at, code.code());
        }
        journal.append(next);
        return next.state() == DurableRecord.State.APPLIED;
    }

    private boolean matches(final ZeroDataEnvelopeStore store, final DurableRecord record) {
        return store.findById(record.envelope().id()).filter(value -> DurableRecord.sameEnvelope(value, record.envelope())).isPresent();
    }

    private ZeroDataEnvelopeStore requireTarget(final String target) {
        ZeroDataEnvelopeStore store = targets.get(target);
        if (store == null) throw ZeroException.of(DataErrorCode.PERSISTENCE_TARGET_NOT_FOUND, "durable target missing", null);
        return store;
    }

    private <T> CompletableFuture<T> enqueue(final Supplier<T> action) {
        synchronized (serial) {
            CompletableFuture<T> result = tail.thenApplyAsync(ignored -> action.get(), io);
            tail = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    private <T> CompletionStage<T> tracked(final Supplier<CompletionStage<T>> action) {
        var result = new CompletableFuture<T>();
        synchronized (admission) {
            if (closing != null || accepted.size() >= 1024) return CompletableFuture.failedFuture(failure("durable admission closed or full", null));
            accepted.add(result);
        }
        result.whenComplete((ignored, failure) -> { synchronized (admission) { accepted.remove(result); } });
        try {
            action.get().whenComplete((value, failure) -> {
                if (failure == null) result.complete(value); else result.completeExceptionally(failure);
            });
        } catch (RuntimeException | Error failure) { result.completeExceptionally(failure); }
        return result.copy();
    }

    private static ZeroException failure(final String message, final Throwable cause) {
        return ZeroException.of(DataErrorCode.PERSISTENCE_FLUSH_FAILED, message, cause);
    }
}
