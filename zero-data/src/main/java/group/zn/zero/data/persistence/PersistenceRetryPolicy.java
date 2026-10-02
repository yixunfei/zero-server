package group.zn.zero.data.persistence;

import java.time.Duration;
import java.util.Objects;

/** 有界指数退避；达到尝试预算后隔离，保留数据等待显式恢复。 @author zn */
public record PersistenceRetryPolicy(int maxAttempts, Duration initialDelay, Duration maxDelay) {
    /** 校验正数尝试预算和非负、有序延迟；不可变，线程安全。 */
    public PersistenceRetryPolicy {
        Objects.requireNonNull(initialDelay, "initialDelay");
        Objects.requireNonNull(maxDelay, "maxDelay");
        if (maxAttempts < 1 || initialDelay.isNegative() || maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("invalid persistence retry policy");
        }
        maxDelay.toMillis();
    }

    /** @return 默认五次尝试，100ms 起步，最大 30 秒；线程安全。 */
    public static PersistenceRetryPolicy defaults() {
        return new PersistenceRetryPolicy(5, Duration.ofMillis(100), Duration.ofSeconds(30));
    }

    /** @param attempts 已失败次数。 @return 饱和计算的延迟毫秒；不修改状态。 */
    public long delayMillis(final int attempts) {
        long value = initialDelay.toMillis();
        long max = maxDelay.toMillis();
        for (int i = 1; i < attempts && value < max; i++) value = value > max / 2 ? max : value * 2;
        return value;
    }
}
