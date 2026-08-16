package group.zn.zero.runtime.spi;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * 基于单调时钟的单个 runtime 阶段累计截止线。
 *
 * <p>实例从创建时开始计时，可分别用于 planning/create 和 start/health；调用方不得跨阶段复用同一实例。</p>
 *
 * @author zn
 */
public final class RuntimeDeadline {

    private final Duration timeout;
    private final long timeoutNanos;
    private final long startedNanos;
    private final LongSupplier nanoTime;

    private RuntimeDeadline(final Duration timeout, final LongSupplier nanoTime) {
        this.timeout = requirePositive(timeout);
        this.timeoutNanos = toNanos(this.timeout);
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
        this.startedNanos = nanoTime.getAsLong();
    }

    /**
     * 使用系统单调时钟创建阶段截止线。
     *
     * @param timeout 阶段总预算；必须大于零。
     * @return deadline；不可为空。
     */
    public static RuntimeDeadline after(final Duration timeout) {
        return new RuntimeDeadline(timeout, System::nanoTime);
    }

    /**
     * 使用可控单调时钟创建阶段截止线。
     *
     * @param timeout 阶段总预算；必须大于零。
     * @param nanoTime 单调纳秒时钟；不可为空。
     * @return deadline；不可为空。
     */
    public static RuntimeDeadline using(final Duration timeout, final LongSupplier nanoTime) {
        return new RuntimeDeadline(timeout, nanoTime);
    }

    /**
     * 返回当前阶段配置的总预算。
     *
     * @return 总预算；不可为空。
     */
    public Duration timeout() {
        return timeout;
    }

    /**
     * 返回当前剩余预算，最小为零。
     *
     * @return 剩余预算；不可为空。
     */
    public Duration remaining() {
        long elapsed = nanoTime.getAsLong() - startedNanos;
        if (elapsed <= 0L) {
            return timeout;
        }
        long remainingNanos = timeoutNanos - elapsed;
        return remainingNanos <= 0L ? Duration.ZERO : Duration.ofNanos(remainingNanos);
    }

    /**
     * 判断当前阶段预算是否已经耗尽。
     *
     * @return true 表示没有剩余预算。
     */
    public boolean expired() {
        return remaining().isZero();
    }

    @Override
    public String toString() {
        return "RuntimeDeadline{timeout=" + timeout + ", remaining=" + remaining() + '}';
    }

    private static Duration requirePositive(final Duration value) {
        Duration checked = Objects.requireNonNull(value, "timeout");
        if (checked.isZero() || checked.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        return checked;
    }

    private static long toNanos(final Duration value) {
        try {
            return value.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
