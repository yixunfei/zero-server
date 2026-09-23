package group.zn.zero.actor.scheduler;

import group.zn.zero.actor.ActorContext;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.LongAccumulator;

/**
 * 基于外部执行器的 Actor 调度器。
 *
 * <pre>
 * dispatch(message)
 *   -> 根据 laneKey 入队
 *   -> 首条消息提交到外部 executor
 *   -> 同一 lane 串行执行
 *   -> handler 异步完成后再继续 drain 同一 lane
 * </pre>
 *
 * 本实现不创建线程池，执行器由 starter 或业务项目统一管理。处理器不得在 Actor 热路径中执行
 * 不可控远程 IO；如需远程 IO，应在远程 IO 执行域完成后重新投递 Actor 消息。
 * <p>线程安全。lane 按哈希分配到 64 个段，入队、出队与回收只持有所属段的短锁；
 * handler、executor 和 completion 回调均在锁外执行。同一段的 lane 共享锁但不共享执行权。
 * 准入包含排队、执行及异步挂起；每批处理有限消息后归还执行器，不能提高单一 Lane 并行度。
 *
 * @author zn
 */
public final class ExecutorActorScheduler implements ActorScheduler, AutoCloseable {

    /** 精确容量与公平性设置。 */
    private final ActorSchedulerConfig config;
    /** 全局未完成许可。 */
    private final AtomicInteger pending = new AtomicInteger();
    /** 当前执行/异步挂起数。 */
    private final AtomicInteger active = new AtomicInteger();
    /** 关闭状态。 */
    private final AtomicBoolean closed = new AtomicBoolean();
    /** 累计结束数。 */
    private final LongAdder completed = new LongAdder();
    /** 累计拒绝数。 */
    private final LongAdder rejected = new LongAdder();
    /** 总排队等待。 */
    private final LongAdder queueWait = new LongAdder();
    /** 最大排队等待。 */
    private final LongAccumulator maxQueueWait = new LongAccumulator(Long::max, 0);
    /** 直接执行器同 Lane 续调蹦床；不同 Lane 保持同步嵌套调用语义。 */
    private final ThreadLocal<DrainRun> currentRun = new ThreadLocal<>();

    /**
     * 外部执行器。
     */
    private final Executor executor;

    /**
     * 消息处理器注册表。
     */
    private final OrderedActorHandlers handlers = new OrderedActorHandlers();

    /**
     * 固定分段；段数必须是 2 的幂，元素在构造完成后不再替换。
     */
    private final LaneStripe[] stripes = new LaneStripe[64];

    /**
     * 创建 executor-backed Actor 调度器。
     *
     * @param executor 外部执行器；不可为空。
     * @throws NullPointerException 当执行器为空时抛出。
     */
    public ExecutorActorScheduler(final Executor executor) {
        this(executor, ActorSchedulerConfig.defaults());
    }

    /**
     * 创建有界调度器；不创建线程，线程安全。
     * @param executor 受框架管理的执行器，不可为空。
     * @param config 不可变预算，不可为空。
     * @throws NullPointerException 参数为空。
     */
    public ExecutorActorScheduler(final Executor executor, final ActorSchedulerConfig config) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new LaneStripe();
        }
    }

    /**
     * 注册 Actor 消息处理器。
     *
     * @param payloadType 消息体类型；不可为空。
     * @param handler 消息处理器；不可为空。
     * @return 注册句柄；调用 close 后取消注册；不可为空；线程安全。
     * @throws ZeroException 当同一消息体类型重复注册时抛出。
     */
    @Override
    public ActorSubscription register(final Class<?> payloadType, final ActorHandler handler) {
        return handlers.register(payloadType, handler);
    }

    /**
     * 提交 Actor 消息。
     *
     * @param message Actor 消息；不可为空。
     * @return 调度完成信号；不可为空；同一 lane 内顺序完成；线程安全。
     * @throws ZeroException 当消息处理器不存在时抛出，必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Void> dispatch(final ActorMessage message) {
        Objects.requireNonNull(message, "message");
        ActorHandler handler = handlers.find(message.payload().getClass());
        Envelope envelope = new Envelope(message, handler, new CompletableFuture<>(), System.nanoTime());
        LaneStripe stripe = stripe(message.laneKey());
        LaneQueue laneQueue;
        boolean shouldSchedule = false;
        synchronized (stripe) {
            if (closed.get()) return reject(ActorErrorCode.SCHEDULER_CLOSED);
            laneQueue = stripe.lanes.get(message.laneKey());
            if ((laneQueue != null && laneQueue.pending >= config.maxPendingPerLane()) || !acquire()) {
                return reject(ActorErrorCode.CAPACITY_EXCEEDED);
            }
            if (laneQueue == null) {
                laneQueue = new LaneQueue();
                stripe.lanes.put(message.laneKey(), laneQueue);
                shouldSchedule = true;
            }
            laneQueue.messages().add(envelope);
            laneQueue.pending++;
        }
        if (shouldSchedule) {
            scheduleDrain(message.laneKey(), stripe, laneQueue);
        }
        return envelope.completion();
    }

    private LaneStripe stripe(final LaneKey laneKey) {
        int hash = laneKey.hashCode();
        return stripes[(hash ^ (hash >>> 16)) & (stripes.length - 1)];
    }

    private void scheduleDrain(final LaneKey laneKey, final LaneStripe stripe, final LaneQueue laneQueue) {
        try {
            executor.execute(() -> runDrain(laneKey, stripe, laneQueue));
        } catch (RuntimeException ex) {
            failLane(laneKey, stripe, laneQueue, ZeroException.of(
                    ActorErrorCode.EXECUTOR_REJECTED, "actor executor rejected", ex));
        }
    }

    private boolean acquire() {
        int count;
        do {
            count = pending.get();
            if (count >= config.maxPendingTotal()) return false;
        } while (!pending.compareAndSet(count, count + 1));
        return true;
    }

    private CompletionStage<Void> reject(final ActorErrorCode code) {
        rejected.increment();
        return CompletableFuture.failedFuture(ZeroException.of(code, code.message(), null));
    }

    private void runDrain(final LaneKey key, final LaneStripe stripe, final LaneQueue queue) {
        DrainRun prior = currentRun.get();
        for (DrainRun cursor = prior; cursor != null; cursor = cursor.parent) {
            if (cursor.queue == queue) {
                cursor.again = true;
                return;
            }
        }
        DrainRun run = new DrainRun(queue, prior);
        currentRun.set(run);
        try {
            do {
                run.again = false;
                drain(key, stripe, queue);
            } while (run.again);
        } finally {
            if (prior == null) currentRun.remove();
            else currentRun.set(prior);
        }
    }

    private void drain(final LaneKey laneKey, final LaneStripe stripe, final LaneQueue laneQueue) {
        for (int count = 0; count < config.maxMessagesPerDrain(); count++) {
            Envelope envelope;
            synchronized (stripe) {
                envelope = laneQueue.messages().poll();
                if (envelope == null) {
                    stripe.lanes.remove(laneKey, laneQueue);
                    return;
                }
            }
            active.incrementAndGet();
            long waited = Math.max(0, System.nanoTime() - envelope.queuedAt());
            queueWait.add(waited);
            maxQueueWait.accumulate(waited);
            CompletableFuture<Void> future = handle(envelope);
            if (future.isDone()) {
                completeEnvelope(envelope, future, stripe, laneQueue);
            } else if (awaitCompletion(envelope, future, laneKey, stripe, laneQueue)) {
                return;
            }
        }
        synchronized (stripe) {
            if (laneQueue.messages().isEmpty()) {
                stripe.lanes.remove(laneKey, laneQueue);
                return;
            }
        }
        scheduleDrain(laneKey, stripe, laneQueue);
    }

    private boolean awaitCompletion(final Envelope envelope, final CompletableFuture<Void> future,
            final LaneKey laneKey, final LaneStripe stripe, final LaneQueue laneQueue) {
        // 0=注册回调中，1=已交出执行权，2=完成。注册期间同步完成则由当前 drain 继续，
        // 避免 direct executor 在 isDone/whenComplete 竞态中不断递归续调并耗尽栈。
        AtomicInteger handoff = new AtomicInteger();
        future.whenComplete((ignored, ex) -> {
            completeEnvelope(envelope, future, stripe, laneQueue);
            if (handoff.getAndSet(2) == 1) scheduleDrain(laneKey, stripe, laneQueue);
        });
        return handoff.compareAndSet(0, 1);
    }

    private CompletableFuture<Void> handle(final Envelope envelope) {
        ActorMessage message = envelope.message();
        try {
            CompletionStage<Void> stage = Objects.requireNonNull(
                    envelope.handler().handle(ActorContext.from(message), message),
                    "actor handler result");
            return Objects.requireNonNull(stage.toCompletableFuture(), "actor handler future");
        } catch (RuntimeException | Error ex) {
            return CompletableFuture.failedFuture(asZeroException(ex));
        }
    }

    private void completeEnvelope(final Envelope envelope, final CompletableFuture<Void> future,
            final LaneStripe stripe, final LaneQueue queue) {
        release(stripe, queue);
        try {
            future.join();
            envelope.completion().complete(null);
        } catch (CompletionException ex) {
            envelope.completion().completeExceptionally(asZeroException(ex));
        } catch (RuntimeException ex) {
            envelope.completion().completeExceptionally(asZeroException(ex));
        }
    }

    private void release(final LaneStripe stripe, final LaneQueue queue) {
        synchronized (stripe) { queue.pending--; }
        active.decrementAndGet();
        pending.decrementAndGet();
        completed.increment();
    }

    private void failLane(final LaneKey laneKey, final LaneStripe stripe,
            final LaneQueue laneQueue, final ZeroException exception) {
        List<Envelope> failed = new ArrayList<>();
        synchronized (stripe) {
            if (!stripe.lanes.remove(laneKey, laneQueue)) {
                return;
            }
            Envelope envelope;
            while ((envelope = laneQueue.messages().poll()) != null) {
                failed.add(envelope);
            }
            laneQueue.pending -= failed.size();
            pending.addAndGet(-failed.size());
            rejected.add(failed.size());
        }
        failed.forEach(envelope -> envelope.completion().completeExceptionally(exception));
    }

    private ZeroException asZeroException(final Throwable throwable) {
        Throwable current = throwable;
        if (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        if (current instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                current.getMessage() == null ? SystemErrorCode.SYSTEM_ERROR.message() : current.getMessage(),
                current);
    }

    /**
     * 待处理消息信封。
     *
     * @param message Actor 消息。
     * @param handler Actor 处理器。
     * @param completion 完成信号。
     * @author zn
     */
    private record Envelope(ActorMessage message, ActorHandler handler, CompletableFuture<Void> completion,
            long queuedAt) {
    }

    /** 包含 lane 状态的锁分段，Map 仅在本对象锁内访问。 @author zn */
    private static final class LaneStripe {
        /** 正在执行或等待异步完成的 lane；空闲即回收。 */
        private final Map<LaneKey, LaneQueue> lanes = new HashMap<>();
    }

    /**
     * lane 内部队列。
     *
     * @author zn
     */
    private static final class LaneQueue {

        /** 含执行中/异步挂起的准入数；只在段锁内访问。 */
        private int pending;

        /**
         * 消息队列。
         */
        private final Queue<Envelope> messages = new ArrayDeque<>();

        /**
         * 返回消息队列。
         *
         * @return 可变消息队列；FIFO 有序，可能为空；仅在所属段锁内访问。
         */
        Queue<Envelope> messages() {
            return messages;
        }

    }

    /** @return 固定维度观察快照；不可变，线程安全，静止后计数精确。 */
    public ActorSchedulerStatistics statistics() {
        return new ActorSchedulerStatistics(pending.get(), active.get(), completed.sum(), rejected.sum(),
                queueWait.sum(), maxQueueWait.get());
    }

    /**
     * 关闭准入并失败通知排队消息；线程安全、幂等，不关闭外部执行器。
     * 正在执行或异步挂起的 handler 继续完成，期间仍占用许可，不能强制中断业务。
     */
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        List<Envelope> failed = new ArrayList<>();
        for (LaneStripe stripe : stripes) {
            synchronized (stripe) {
                for (LaneQueue queue : stripe.lanes.values()) {
                    int count = queue.messages.size();
                    failed.addAll(queue.messages);
                    queue.messages.clear();
                    queue.pending -= count;
                    pending.addAndGet(-count);
                }
                stripe.lanes.values().removeIf(queue -> queue.pending == 0);
            }
        }
        rejected.add(failed.size());
        ZeroException failure = ZeroException.of(ActorErrorCode.SCHEDULER_CLOSED, "actor scheduler closed", null);
        failed.forEach(envelope -> envelope.completion().completeExceptionally(failure));
    }

    /** 调用线程私有的直接执行器续调记录。 @author zn */
    private static final class DrainRun {
        /** 当前 Lane。 */
        private final LaneQueue queue;
        /** 跨 Lane 同步嵌套的上层记录。 */
        private final DrainRun parent;
        /** 本次退出后是否迭代续调。 */
        private boolean again;
        private DrainRun(final LaneQueue queue, final DrainRun parent) {
            this.queue = queue;
            this.parent = parent;
        }
    }
}
