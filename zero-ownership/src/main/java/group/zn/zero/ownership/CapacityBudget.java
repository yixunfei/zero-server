package group.zn.zero.ownership;

import java.time.Duration;
import java.util.Objects;

/**
 * 有界并发容量预算；只在调用线程上等待，不创建后台线程。
 * drain 会停止接收新请求并等待已有 permit 释放，close 会立即唤醒等待者。
 */
public final class CapacityBudget implements AutoCloseable {
    private final int maxConcurrent;
    private final int maxQueued;
    private int active;
    private int queued;
    private boolean draining;
    private boolean closed;

    /** 创建容量预算。 */
    public CapacityBudget(final int maxConcurrent, final int maxQueued) {
        if (maxConcurrent <= 0 || maxQueued < 0) {
            throw new IllegalArgumentException("capacity values are invalid");
        }
        this.maxConcurrent = maxConcurrent;
        this.maxQueued = maxQueued;
    }

    /** 立即尝试获取 permit，容量不足时返回空。 */
    public synchronized java.util.Optional<Permit> tryAcquire() {
        ensureAccepting();
        if (active >= maxConcurrent) {
            return java.util.Optional.empty();
        }
        active++;
        return java.util.Optional.of(new Permit(this));
    }

    /** 在指定期限内获取 permit；超时或排队上限触发容量拒绝。 */
    public Permit acquire(final Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        long remaining = timeout.toNanos();
        final long deadline = remaining > Long.MAX_VALUE - System.nanoTime()
                ? Long.MAX_VALUE : System.nanoTime() + remaining;
        synchronized (this) {
            ensureAccepting();
            while (active >= maxConcurrent) {
                if (queued >= maxQueued) {
                    throw new CapacityRejectedException("capacity wait queue is full");
                }
                queued++;
                try {
                    while (active >= maxConcurrent && !closed && !draining) {
                        long nanos = deadline - System.nanoTime();
                        if (nanos <= 0) {
                            throw new CapacityRejectedException("capacity deadline exceeded");
                        }
                        long millis = nanos / 1_000_000L;
                        int nanosPart = (int) (nanos % 1_000_000L);
                        wait(millis, nanosPart);
                    }
                    if (closed || draining) {
                        throw new CapacityRejectedException("capacity budget is draining or closed");
                    }
                } finally {
                    queued--;
                }
            }
            active++;
            return new Permit(this);
        }
    }

    /** 当前活动 permit 数。 */
    public synchronized int activeCount() {
        return active;
    }

    /** 当前等待数。 */
    public synchronized int queuedCount() {
        return queued;
    }

    /** 返回当前预算快照；结果不可变，可安全交给观察器。 */
    public synchronized CapacitySnapshot snapshot() {
        return new CapacitySnapshot(maxConcurrent, maxQueued, active, queued, draining, closed);
    }

    /**
     * 等待当前 permit 全部释放；超时返回 false，调用方可据此决定是否强制关闭。
     * 不会接收新请求，也不会创建后台线程。
     */
    public synchronized boolean awaitDrained(final Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be non-negative");
        }
        draining = true;
        notifyAll();
        long remaining = timeout.toNanos();
        final long deadline = remaining > Long.MAX_VALUE - System.nanoTime()
                ? Long.MAX_VALUE : System.nanoTime() + remaining;
        while (active > 0) {
            long nanos = deadline - System.nanoTime();
            if (nanos <= 0) {
                return false;
            }
            wait(nanos / 1_000_000L, (int) (nanos % 1_000_000L));
        }
        return true;
    }

    /** 开始排空；已有 permit 可继续释放。 */
    public synchronized void drain() {
        draining = true;
        notifyAll();
    }

    /** 关闭并唤醒所有等待调用者。 */
    @Override
    public synchronized void close() {
        closed = true;
        draining = true;
        notifyAll();
    }

    private void ensureAccepting() {
        if (closed || draining) {
            throw new CapacityRejectedException("capacity budget is draining or closed");
        }
    }

    private synchronized void releasePermit(final Permit permit) {
        if (!permit.released) {
            permit.released = true;
            active--;
            notifyAll();
        }
    }

    /** 一个可重复 close 的容量许可。 */
    public static final class Permit implements AutoCloseable {
        private final CapacityBudget budget;
        private boolean released;

        private Permit(final CapacityBudget budget) {
            this.budget = budget;
        }

        /** 释放许可；重复调用不会减少容量计数。 */
        @Override
        public void close() {
            budget.releasePermit(this);
        }
    }
}
