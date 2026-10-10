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
    /** 由组合根拥有的超时计时器；确定性直接执行器可不配置。 */
    private final java.util.concurrent.ScheduledExecutorService timeoutTimer;
    /** 单次实际执行预算，不包含排队时间。 */
    private final long executionTimeoutNanos;
    /** 累计超时隔离次数。 */
    private final LongAdder timedOut = new LongAdder();
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
    /**
     * 直接执行器同 Lane 续调蹦床；不同 Lane 保持同步嵌套调用语义。
     *
     * <p>static 而非实例级：实例级 ThreadLocal 的 key 随调度器实例 GC 后被弱引用清除，但 value
     * 链（DrainRun→LaneQueue→handler）会滞留在长寿命池线程的 ThreadLocalMap 中直到槽位复用，
     * 频繁重建调度器时造成缓慢内存滞留。static key 永不过期，槽位中最多保留一条最近活跃链，
     * 同线程后续调度器实例的嵌套 set/remove 会自然复用并覆盖同一槽位。
     * 嵌套 finally 中恢复 prior 的语义必须保留：三层以上嵌套（A→B→C）时入口重入检测
     * （runDrain 开头沿 prior 链查同队列）依赖中间层结束后链上仍有外层节点；若内层 finally
     * 无条件 remove，更深层将失去跨层同队列重入检测，可能同线程递归 drain 同一队列。
     * owner 字段用于跳过其它调度器实例遗留的节点，避免跨实例误判同队列重入。
     */
    private static final ThreadLocal<DrainRun> currentRun = new ThreadLocal<>();

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
        this(executor, config, null, java.time.Duration.ZERO);
    }

    /**
     * 使用受管计时器观察执行超时；不创建线程，也不关闭注入的 timer。
     * 超时失败通知调用方并拒绝该 lane 排队/新消息，直到真实 handler 完成才释放许可。
     * @param executor 逻辑执行器。 @param config 队列预算。 @param timer 受管计时器，可空表示关闭观察。
     * @param executionTimeout 正执行预算；timer 为空时必须为零。线程安全。
     */
    public ExecutorActorScheduler(final Executor executor, final ActorSchedulerConfig config,
            final java.util.concurrent.ScheduledExecutorService timer, final java.time.Duration executionTimeout) {
        this.executor = Objects.requireNonNull(executor, "executor");
        this.config = Objects.requireNonNull(config, "config");
        timeoutTimer = timer;
        executionTimeoutNanos = Objects.requireNonNull(executionTimeout, "executionTimeout").toNanos();
        if (timer == null ? executionTimeoutNanos != 0 : executionTimeoutNanos <= 0) {
            throw new IllegalArgumentException("timer requires a positive execution timeout");
        }
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
            if (laneQueue != null && laneQueue.isolated) return reject(ActorErrorCode.EXECUTION_TIMEOUT);
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
        } catch (RuntimeException | Error ex) {
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
        // 沿链只认本调度器实例的节点：static ThreadLocal 下链上可能滞留其它已丢弃实例的
        // DrainRun，其 queue 引用属于旧实例的分段状态，不能参与本实例的同队列重入判定。
        DrainRun prior = currentRun.get();
        for (DrainRun cursor = prior; cursor != null; cursor = cursor.parent) {
            if (cursor.owner == this && cursor.queue == queue) {
                cursor.again = true;
                return;
            }
        }
        DrainRun run = new DrainRun(this, queue, prior);
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
            ExecutionWatch watch = new ExecutionWatch(envelope, stripe, laneQueue);
            CompletionStage<Void> stage;
            try {
                watch.start();
                stage = handle(envelope);
            } catch (RuntimeException | Error failure) {
                stage = CompletableFuture.failedFuture(failure);
            }
            if (stage.getClass() == CompletableFuture.class && ((CompletableFuture<?>) stage).isDone()) {
                Throwable failure = null;
                try { ((CompletableFuture<?>) stage).join(); }
                catch (RuntimeException ex) { failure = ex; }
                watch.finish(failure);
            } else if (awaitCompletion(envelope, stage, laneKey, stripe, laneQueue, watch)) {
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

    private boolean awaitCompletion(final Envelope envelope, final CompletionStage<Void> stage,
            final LaneKey laneKey, final LaneStripe stripe, final LaneQueue laneQueue, final ExecutionWatch watch) {
        // 0=注册回调中，1=已交出执行权，2=完成。注册期间同步完成则由当前 drain 继续，
        // 避免 direct executor 在 isDone/whenComplete 竞态中不断递归续调并耗尽栈。
        AtomicInteger handoff = new AtomicInteger();
        AtomicBoolean claimed = new AtomicBoolean();
        java.util.function.BiConsumer<Void, Throwable> completion = (ignored, ex) -> {
            if (!claimed.compareAndSet(false, true)) return;
            watch.finish(ex);
            if (handoff.getAndSet(2) == 1) scheduleDrain(laneKey, stripe, laneQueue);
        };
        try { stage.whenComplete(completion); }
        catch (RuntimeException | Error failure) { completion.accept(null, failure); }
        return handoff.compareAndSet(0, 1);
    }

    private CompletionStage<Void> handle(final Envelope envelope) {
        ActorMessage message = envelope.message();
        try {
            return Objects.requireNonNull(
                    envelope.handler().handle(ActorContext.from(message), message),
                    "actor handler result");
        } catch (RuntimeException | Error ex) {
            return CompletableFuture.failedFuture(asZeroException(ex));
        }
    }

    private void completeEnvelope(final Envelope envelope, final Throwable failure,
            final LaneStripe stripe, final LaneQueue queue) {
        release(stripe, queue, envelope.message().laneKey());
        if (failure == null) envelope.completion().complete(null);
        else envelope.completion().completeExceptionally(asZeroException(failure));
    }

    private void release(final LaneStripe stripe, final LaneQueue queue, final LaneKey laneKey) {
        synchronized (stripe) {
            queue.pending--;
            if (closed.get() && queue.pending == 0) stripe.lanes.remove(laneKey, queue);
        }
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
        // 对外消息不拷贝底层异常文本：getMessage() 可能含 SQL/连接串/凭据/玩家输入，
        // 且会经完成信号进入业务可见的异常文本。原始异常仅作 cause 保留（不入对外消息），
        // 与 RuntimeAssemblyException 的安全消息裁剪一致。
        return ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR,
                SystemErrorCode.SYSTEM_ERROR.message(),
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

        /** 当前 handler 超时；真实完成前拒绝后续准入。 */
        private boolean isolated;

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

    /** @return 累计执行超时次数；线程安全，不暴露高基数 lane 标识。 */
    public long executionTimeoutCount() { return timedOut.sum(); }

    /** 每条执行的超时所有权；所有状态由 lane 段锁保护，通知始终在锁外。 */
    private final class ExecutionWatch {
        /** 被观察的消息。 */
        private final Envelope envelope;
        /** lane 状态锁。 */
        private final LaneStripe stripe;
        /** 被观察队列。 */
        private final LaneQueue queue;
        /** 真实 handler 是否完成。 */
        private boolean finished;
        /** 定时句柄；在注册回调前发布。 */
        private java.util.concurrent.ScheduledFuture<?> timer;
        private ExecutionWatch(Envelope envelope, LaneStripe stripe, LaneQueue queue) {
            this.envelope = envelope; this.stripe = stripe; this.queue = queue;
        }
        private void start() {
            if (timeoutTimer != null) timer = timeoutTimer.schedule(this::expire,
                    executionTimeoutNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
        private void expire() {
            List<Envelope> rejectedMessages;
            synchronized (stripe) {
                if (finished) return;
                queue.isolated = true;
                timedOut.increment();
                rejectedMessages = new ArrayList<>(queue.messages);
                queue.messages.clear();
                queue.pending -= rejectedMessages.size();
                pending.addAndGet(-rejectedMessages.size());
                rejected.add(rejectedMessages.size());
            }
            ZeroException failure = ZeroException.of(ActorErrorCode.EXECUTION_TIMEOUT);
            envelope.completion().completeExceptionally(failure);
            rejectedMessages.forEach(message -> message.completion().completeExceptionally(failure));
        }
        private void finish(final Throwable failure) {
            Throwable outcome;
            synchronized (stripe) {
                finished = true;
                outcome = queue.isolated ? ZeroException.of(ActorErrorCode.EXECUTION_TIMEOUT) : failure;
            }
            if (timer != null) timer.cancel(false);
            completeEnvelope(envelope, outcome, stripe, queue);
        }
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
        /** 所属调度器实例；static ThreadLocal 下用于跳过其它实例遗留的节点。 */
        private final ExecutorActorScheduler owner;
        /** 当前 Lane。 */
        private final LaneQueue queue;
        /** 跨 Lane 同步嵌套的上层记录。 */
        private final DrainRun parent;
        /** 本次退出后是否迭代续调。 */
        private boolean again;
        private DrainRun(final ExecutorActorScheduler owner, final LaneQueue queue, final DrainRun parent) {
            this.owner = owner;
            this.queue = queue;
            this.parent = parent;
        }
    }
}
