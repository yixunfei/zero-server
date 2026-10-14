package group.zn.zero.examples.business;

import group.zn.zero.actor.LaneKey;
import group.zn.zero.data.model.PageRequest;
import group.zn.zero.data.repository.CrudRepository;
import group.zn.zero.game.ActorRequestExecutor;
import group.zn.zero.runtime.bootstrap.PeriodicAsyncTask;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** 受管、单次在途的通知重放；每轮至多 64 条，按玩家 lane 串行，消费者必须按稳定事件 ID 去重。 @author zn */
public final class DailyRewardNotificationReplay implements AutoCloseable {
    /** 聚合仓库。 */
    private final CrudRepository<Long, DailyRewardInventory> repository;
    /** 原业务服务。 */
    private final DailyRewardService service;
    /** 与请求共用的玩家执行域。 */
    private final ActorRequestExecutor actors;
    /** 完成感知的受管任务。 */
    private final PeriodicAsyncTask task;
    /** 周期任务串行持有的分页位置。 */
    private int offset;

    /** @param repository 聚合仓库。 @param service 业务服务。 @param actors 共用 Actor 入口。
     * @param executors 受管执行器。 @param interval 正间隔。构造不启动任务，关闭顺序先 replay 再 actors。 */
    public DailyRewardNotificationReplay(final CrudRepository<Long, DailyRewardInventory> repository,
            final DailyRewardService service, final ActorRequestExecutor actors,
            final ZeroRuntimeExecutors executors, final Duration interval) {
        this.repository = java.util.Objects.requireNonNull(repository); this.service = java.util.Objects.requireNonNull(service);
        this.actors = java.util.Objects.requireNonNull(actors);
        task = new PeriodicAsyncTask(executors, "daily-reward-notifications", interval, this::replayPage);
    }
    /** 启动自动恢复；线程安全，启动失败直接传播。 */
    public void start() { task.start(); }
    /** @return 失败轮次数；线程安全。 */
    public long failureCount() { return task.failureCount(); }
    /** @return 最近失败，或 null；只供内部诊断，线程安全。 */
    public Throwable lastFailure() { return task.lastFailure(); }
    /** 停止准入并排空在途重放；失败明确传播，不关闭共享执行器。 */
    @Override public void close() { task.stop(); }

    private CompletionStage<Void> replayPage() {
        return repository.findPage(new PageRequest(offset, 64)).thenCompose(page -> {
            var failure = new AtomicReference<Throwable>();
            CompletionStage<Void> chain = CompletableFuture.completedFuture(null);
            for (var inventory : page.items()) {
                if (!inventory.pendingNotification()) continue;
                chain = chain.thenCompose(ignored -> actors.submit(LaneKey.player(inventory.id().toString()),
                        () -> service.replayNotification(inventory.id())).handle((value, problem) -> {
                            if (problem != null) failure.compareAndSet(null, problem);
                            return (Void) null;
                        }));
            }
            return chain.thenCompose(ignored -> {
                long next = (long) offset + page.items().size();
                offset = page.items().isEmpty() || next >= page.total() || next > Integer.MAX_VALUE ? 0 : (int) next;
                return failure.get() == null ? CompletableFuture.completedFuture(null)
                        : CompletableFuture.failedFuture(failure.get());
            });
        });
    }
}
