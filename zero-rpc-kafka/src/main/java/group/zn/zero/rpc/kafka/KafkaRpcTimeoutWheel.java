package group.zn.zero.rpc.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;


/**
 * Kafka RPC pending 请求超时时间轮。
 *
 * <p>该实现使用外部受管计时器按固定 tick 扫描桶位，避免为每个 pending 请求创建
 * {@code ScheduledFuture}。每个登记返回可取消的桶记录，完成、失败或取消时立即以 O(1)
 * 移除，避免长超时和高吞吐使已完成记录绕过 pending 容量限制。</p>
 *
 * @author zn
 */
final class KafkaRpcTimeoutWheel implements AutoCloseable {

    /**
     * 默认 tick 间隔。
     */
    static final Duration DEFAULT_TICK_DURATION = Duration.ofMillis(10);

    /**
     * 默认时间轮桶数量。
     */
    static final int DEFAULT_WHEEL_SIZE = 512;

    /**
     * 时间轮内部日志器。
     */
    private static final System.Logger LOGGER = System.getLogger(KafkaRpcTimeoutWheel.class.getName());

    /**
     * tick 间隔毫秒数。
     */
    private final long tickMillis;

    /**
     * tick 间隔纳秒数。
     */
    private final long tickNanos;

    /**
     * 时间轮桶数量。
     */
    private final int wheelSize;

    /**
     * 超时回调。
     */
    private final TimeoutHandler timeoutHandler;

    /**
     * 时间轮桶数组。
     */
    private final Map<Long, TimeoutTask>[] buckets;

    /**
     * 是否正在运行。
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 当前 tick。
     */
    private final AtomicLong currentTick = new AtomicLong();

    /**
     * 外部计时器上的任务句柄；关闭只取消任务，不关闭外部执行器。
     */
    private final java.util.concurrent.ScheduledFuture<?> ticking;

    /**
     * 创建默认时间轮。
     *
     * @param timeoutHandler 超时回调；不可为空。
     * @throws NullPointerException 当超时回调为空时抛出。
     */
    KafkaRpcTimeoutWheel(final TimeoutHandler timeoutHandler, final java.util.concurrent.ScheduledExecutorService timer) {
        this(DEFAULT_TICK_DURATION, DEFAULT_WHEEL_SIZE, timeoutHandler, timer);
    }

    /**
     * 创建时间轮。
     *
     * @param tickDuration tick 间隔；不可为空，必须为正数。
     * @param wheelSize 时间轮桶数量；必须为正数。
     * @param timeoutHandler 超时回调；不可为空。
     * @throws NullPointerException 当 tick 间隔或超时回调为空时抛出。
     * @throws IllegalArgumentException 当 tick 间隔或桶数量非法时抛出。
     */
    @SuppressWarnings("unchecked")
    KafkaRpcTimeoutWheel(
            final Duration tickDuration,
            final int wheelSize,
            final TimeoutHandler timeoutHandler, final java.util.concurrent.ScheduledExecutorService timer) {
        Duration currentTickDuration = Objects.requireNonNull(tickDuration, "tickDuration");
        if (currentTickDuration.isNegative() || currentTickDuration.isZero()) {
            throw new IllegalArgumentException("tickDuration must be positive");
        }
        if (wheelSize <= 0) {
            throw new IllegalArgumentException("wheelSize must be positive");
        }
        this.tickMillis = Math.max(1L, currentTickDuration.toMillis());
        this.tickNanos = Math.max(1L, currentTickDuration.toNanos());
        this.wheelSize = wheelSize;
        this.timeoutHandler = Objects.requireNonNull(timeoutHandler, "timeoutHandler");
        this.buckets = (Map<Long, TimeoutTask>[]) new Map<?, ?>[wheelSize];
        for (int index = 0; index < wheelSize; index++) {
            buckets[index] = new HashMap<>();
        }
        this.ticking = Objects.requireNonNull(timer, "timer").scheduleWithFixedDelay(
                this::tick, tickNanos, tickNanos, java.util.concurrent.TimeUnit.NANOSECONDS);
    }

    /**
     * 调度一个超时任务；时间轮已关闭时返回 null，调用方必须按注册失败兜底。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param timeoutAt 超时时间；不可为空。
     * @throws NullPointerException 当关联 ID 或超时时间为空时抛出。
     */
    TimeoutTask schedule(final String correlationId, final Instant timeoutAt, final long token) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(timeoutAt, "timeoutAt");
        if (!running.get()) {
            return null;
        }
        TimeoutTask task = new TimeoutTask(correlationId, timeoutAt.toEpochMilli(), token);
        if (!enqueue(task)) {
            return null;
        }
        return task;
    }

    /** 同一记录的重排与取消串行；桶扫描不持有记录锁，避免锁顺序反转。已关闭时返回 false。 */
    private boolean enqueue(final TimeoutTask task) {
        synchronized (task) {
            if (task.cancelled) return false;
            return enqueueActive(task);
        }
    }

    private boolean enqueueActive(final TimeoutTask task) {
        while (running.get()) {
            long delayMillis = Math.max(1L, task.deadlineMillis() - System.currentTimeMillis());
            long delayTicks = Math.max(1L, divideCeil(delayMillis, tickMillis)) + 1L;
            long tick = currentTick.get();
            long rounds = (delayTicks - 1L) / wheelSize;
            Map<Long, TimeoutTask> bucket = buckets[bucketIndex(tick + delayTicks)];
            synchronized (bucket) {
                if (!running.get()) return false;
                // 等待桶锁期间时间轮可能已经扫描目标桶，重新选桶避免多等一轮。
                if (currentTick.get() != tick) continue;
                task.remainingRounds = rounds;
                task.bucket = bucket;
                bucket.put(task.token(), task);
                return true;
            }
        }
        return false;
    }

    /** 取消指定登记，立即释放桶记录；与重排并发安全，不影响复用 correlationId 的请求。 */
    void cancel(final TimeoutTask task) {
        if (task == null) return;
        synchronized (task) {
            task.cancelled = true;
            if (task.bucket != null) {
                synchronized (task.bucket) { task.bucket.remove(task.token(), task); }
            }
        }
    }

    /**
     * 关闭时间轮并清空未触发任务。
     */
    @Override
    public void close() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        ticking.cancel(false);
        for (Map<Long, TimeoutTask> bucket : buckets) {
            synchronized (bucket) {
                bucket.clear();
            }
        }
    }

    /** Tick 由组合根受管计时器调用；关闭后不再推进。 */
    private void tick() {
        if (!running.get()) return;
        long tick = currentTick.incrementAndGet();
        expireBucket(bucketIndex(tick), System.currentTimeMillis());
    }

    private void expireBucket(final int bucketIndex, final long nowMillis) {
        Map<Long, TimeoutTask> bucket = buckets[bucketIndex];
        ArrayDeque<TimeoutTask> dueTasks = new ArrayDeque<>();
        synchronized (bucket) {
            Iterator<TimeoutTask> iterator = bucket.values().iterator();
            while (iterator.hasNext()) {
                TimeoutTask task = iterator.next();
                if (task.remainingRounds() > 0L) {
                    task.decreaseRemainingRounds();
                } else {
                    iterator.remove();
                    dueTasks.addLast(task);
                }
            }
        }
        while (running.get() && !dueTasks.isEmpty()) {
            TimeoutTask task = dueTasks.removeFirst();
            if (task.cancelled) continue;
            if (nowMillis >= task.deadlineMillis()) {
                notifyTimeout(task);
            } else {
                enqueue(task);
            }
        }
    }

    private void notifyTimeout(final TimeoutTask task) {
        try {
            timeoutHandler.onTimeout(task.correlationId(), task.token());
        } catch (RuntimeException | Error failure) {
            LOGGER.log(System.Logger.Level.ERROR,
                    "kafka rpc pending timeout callback failed");
        }
    }

    private int bucketIndex(final long tick) {
        return (int) Math.floorMod(tick, wheelSize);
    }

    private static long divideCeil(final long value, final long divisor) {
        return Math.floorDiv(value - 1L, divisor) + 1L;
    }

    /**
     * 时间轮超时回调。
     *
     * @author zn
     */
    @FunctionalInterface
    interface TimeoutHandler {

        /**
         * 处理超时任务。
         *
         * @param correlationId 关联 ID；不可为空。
         * @param token pending 内部令牌。
         */
        void onTimeout(String correlationId, long token);
    }

    /**
     * 时间轮超时任务。
     *
     * @author zn
     */
    static final class TimeoutTask {

        /** 所属桶；只在记录锁内变更和读取。 */
        private Map<Long, TimeoutTask> bucket;

        /** 取消在记录锁内写入，桶扫描在锁外读取。 */
        private volatile boolean cancelled;

        /**
         * correlationId。
         */
        private final String correlationId;

        /**
         * 超时时间戳，单位毫秒。
         */
        private final long deadlineMillis;

        /**
         * 剩余轮数。
         */
        private long remainingRounds;

        /**
         * pending 内部令牌。
         */
        private final long token;

        /**
         * 创建超时任务。
         *
         * @param correlationId 关联 ID；不可为空。
         * @param deadlineMillis 超时时间戳，单位毫秒。
         * @param token pending 内部令牌。
         */
        private TimeoutTask(
                final String correlationId,
                final long deadlineMillis,
                final long token) {
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
            this.deadlineMillis = deadlineMillis;
            this.token = token;
        }

        /**
         * 返回关联 ID。
         *
         * @return 关联 ID；不可为空；线程安全。
         */
        private String correlationId() {
            return correlationId;
        }

        /**
         * 返回超时时间戳。
         *
         * @return 超时时间戳，单位毫秒；线程安全。
         */
        private long deadlineMillis() {
            return deadlineMillis;
        }

        /**
         * 返回剩余轮数。
         *
         * @return 剩余轮数；调用方需在桶锁内访问。
         */
        private long remainingRounds() {
            return remainingRounds;
        }

        /**
         * 递减剩余轮数。
         */
        private void decreaseRemainingRounds() {
            remainingRounds--;
        }

        /**
         * 返回 pending 内部令牌。
         *
         * @return pending 内部令牌；线程安全。
         */
        private long token() {
            return token;
        }
    }
}
