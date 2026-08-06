package group.zn.zero.rpc.kafka;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.LockSupport;

/**
 * Kafka RPC pending 请求超时时间轮。
 *
 * <p>该实现使用单个后台线程按固定 tick 扫描桶位，避免为每个 pending 请求创建
 * {@code ScheduledFuture}。完成或失败的请求不需要从桶内移除，超时回调触发时由 pending 表
 * 自身做幂等删除。</p>
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
    private final ArrayDeque<TimeoutTask>[] buckets;

    /**
     * 是否正在运行。
     */
    private final AtomicBoolean running = new AtomicBoolean(true);

    /**
     * 当前 tick。
     */
    private final AtomicLong currentTick = new AtomicLong();

    /**
     * 后台 worker 线程。
     */
    private final Thread worker;

    /**
     * 创建默认时间轮。
     *
     * @param timeoutHandler 超时回调；不可为空。
     * @throws NullPointerException 当超时回调为空时抛出。
     */
    KafkaRpcTimeoutWheel(final TimeoutHandler timeoutHandler) {
        this(DEFAULT_TICK_DURATION, DEFAULT_WHEEL_SIZE, timeoutHandler);
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
            final TimeoutHandler timeoutHandler) {
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
        this.buckets = (ArrayDeque<TimeoutTask>[]) new ArrayDeque<?>[wheelSize];
        for (int index = 0; index < wheelSize; index++) {
            buckets[index] = new ArrayDeque<>();
        }
        this.worker = new Thread(this::run, "zero-rpc-kafka-pending-time-wheel");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    /**
     * 调度一个超时任务。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param timeoutAt 超时时间；不可为空。
     * @throws NullPointerException 当关联 ID 或超时时间为空时抛出。
     */
    void schedule(final String correlationId, final Instant timeoutAt, final long token) {
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(timeoutAt, "timeoutAt");
        if (!running.get()) {
            return;
        }
        long delayMillis = Math.max(1L, timeoutAt.toEpochMilli() - System.currentTimeMillis());
        long delayTicks = Math.max(1L, divideCeil(delayMillis, tickMillis)) + 1L;
        long tick = currentTick.get();
        long rounds = (delayTicks - 1L) / wheelSize;
        int bucketIndex = bucketIndex(tick + delayTicks);
        TimeoutTask task = new TimeoutTask(correlationId, timeoutAt.toEpochMilli(), rounds, token);
        ArrayDeque<TimeoutTask> bucket = buckets[bucketIndex];
        synchronized (bucket) {
            if (!running.get()) {
                return;
            }
            bucket.addLast(task);
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
        LockSupport.unpark(worker);
        worker.interrupt();
        joinWorker();
        for (ArrayDeque<TimeoutTask> bucket : buckets) {
            synchronized (bucket) {
                bucket.clear();
            }
        }
    }

    private void run() {
        long nextTickAt = System.nanoTime() + tickNanos;
        while (running.get()) {
            long waitNanos = nextTickAt - System.nanoTime();
            if (waitNanos > 0L) {
                LockSupport.parkNanos(waitNanos);
            }
            if (!running.get()) {
                break;
            }
            if (Thread.interrupted() && !running.get()) {
                break;
            }
            long tick = currentTick.incrementAndGet();
            expireBucket(bucketIndex(tick), System.currentTimeMillis());
            nextTickAt = nextTickAt + tickNanos;
            long now = System.nanoTime();
            if (nextTickAt < now - tickNanos) {
                nextTickAt = now + tickNanos;
            }
        }
    }

    private void expireBucket(final int bucketIndex, final long nowMillis) {
        ArrayDeque<TimeoutTask> bucket = buckets[bucketIndex];
        ArrayDeque<TimeoutTask> dueTasks = new ArrayDeque<>();
        ArrayDeque<TimeoutTask> postponedTasks = new ArrayDeque<>();
        synchronized (bucket) {
            int size = bucket.size();
            for (int index = 0; index < size; index++) {
                TimeoutTask task = bucket.removeFirst();
                if (task.remainingRounds() > 0L) {
                    task.decreaseRemainingRounds();
                    postponedTasks.addLast(task);
                } else {
                    dueTasks.addLast(task);
                }
            }
            bucket.addAll(postponedTasks);
        }
        while (!dueTasks.isEmpty()) {
            TimeoutTask task = dueTasks.removeFirst();
            if (nowMillis >= task.deadlineMillis()) {
                notifyTimeout(task);
            } else {
                schedule(task.correlationId(), Instant.ofEpochMilli(task.deadlineMillis()), task.token());
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

    private void joinWorker() {
        try {
            worker.join(Math.max(100L, tickMillis * 2L));
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
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
    private static final class TimeoutTask {

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
         * @param remainingRounds 剩余轮数。
         * @param token pending 内部令牌。
         */
        private TimeoutTask(
                final String correlationId,
                final long deadlineMillis,
                final long remainingRounds,
                final long token) {
            this.correlationId = Objects.requireNonNull(correlationId, "correlationId");
            this.deadlineMillis = deadlineMillis;
            this.remainingRounds = remainingRounds;
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
