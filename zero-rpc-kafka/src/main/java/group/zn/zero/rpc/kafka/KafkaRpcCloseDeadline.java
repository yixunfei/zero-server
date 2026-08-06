package group.zn.zero.rpc.kafka;

import java.time.Duration;
import java.util.Objects;

/**
 * Kafka RPC 资源关闭的共享绝对截止时间。
 *
 * <p>同一次关闭事务中的所有 consumer 与 producer 共享一个预算，避免订阅数量增加时把
 * {@code closeTimeout} 线性重复累加。</p>
 *
 * @author zn
 */
final class KafkaRpcCloseDeadline {

    /** 防止纳秒差值跨越有符号 long 的半区间。 */
    private static final long MAX_TIMEOUT_NANOS = Long.MAX_VALUE / 4L;

    /** 基于 {@link System#nanoTime()} 的绝对截止点。 */
    private final long deadlineNanos;

    /**
     * 创建关闭截止时间。
     *
     * @param timeout 总关闭预算；不可为空、不可为负。
     */
    private KafkaRpcCloseDeadline(final Duration timeout) {
        Duration current = Objects.requireNonNull(timeout, "timeout");
        if (current.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        deadlineNanos = System.nanoTime() + cappedNanos(current);
    }

    /**
     * 基于总预算创建共享截止时间。
     *
     * @param timeout 总关闭预算；不可为空、不可为负。
     * @return 共享截止时间；不可为空；线程安全。
     */
    static KafkaRpcCloseDeadline after(final Duration timeout) {
        return new KafkaRpcCloseDeadline(timeout);
    }

    /**
     * 返回剩余预算。
     *
     * @return 剩余预算；不可为空、不会为负；线程安全。
     */
    Duration remaining() {
        long remainingNanos = deadlineNanos - System.nanoTime();
        return remainingNanos <= 0L ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    /**
     * 在剩余预算内等待线程退出。
     *
     * @param thread 目标线程；不可为空。
     * @throws InterruptedException 当当前等待线程被中断时抛出。
     */
    void join(final Thread thread) throws InterruptedException {
        long nanos = remaining().toNanos();
        if (nanos <= 0L) {
            return;
        }
        long millis = nanos / 1_000_000L;
        int nanosPart = (int) (nanos % 1_000_000L);
        thread.join(millis, nanosPart);
    }

    private long cappedNanos(final Duration timeout) {
        try {
            return Math.min(timeout.toNanos(), MAX_TIMEOUT_NANOS);
        } catch (ArithmeticException ignored) {
            return MAX_TIMEOUT_NANOS;
        }
    }
}
