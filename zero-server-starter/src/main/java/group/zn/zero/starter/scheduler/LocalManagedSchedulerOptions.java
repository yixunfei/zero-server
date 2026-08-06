package group.zn.zero.starter.scheduler;

import java.time.Duration;
import java.util.Objects;

/**
 * 本地受管定时任务运行参数。
 *
 * @param maxTasks 最大活动任务数量。
 * @param maxInFlight 最大已提交或异步未完成执行数量。
 * @param stopTimeout 停止等待在途任务完成的超时。
 * @param threadNamePrefix timer 线程名前缀。
 * @author zn
 */
public record LocalManagedSchedulerOptions(
        int maxTasks,
        int maxInFlight,
        Duration stopTimeout,
        String threadNamePrefix) {

    /**
     * 默认活动任务上限。
     */
    public static final int DEFAULT_MAX_TASKS = 1024;

    /**
     * 默认在途执行上限。
     */
    public static final int DEFAULT_MAX_IN_FLIGHT = 256;

    /**
     * 默认停止超时。
     */
    public static final Duration DEFAULT_STOP_TIMEOUT = Duration.ofSeconds(3);

    /**
     * 默认 timer 线程名前缀。
     */
    public static final String DEFAULT_THREAD_NAME_PREFIX = "zero-scheduler";

    /**
     * 标准化本地调度参数。
     *
     * @throws NullPointerException 当停止超时或线程名前缀为空时抛出。
     * @throws IllegalArgumentException 当数量、超时或线程名前缀非法时抛出。
     */
    public LocalManagedSchedulerOptions {
        if (maxTasks <= 0) {
            throw new IllegalArgumentException("maxTasks must be positive");
        }
        if (maxInFlight <= 0) {
            throw new IllegalArgumentException("maxInFlight must be positive");
        }
        stopTimeout = Objects.requireNonNull(stopTimeout, "stopTimeout");
        if (stopTimeout.isZero() || stopTimeout.isNegative()) {
            throw new IllegalArgumentException("stopTimeout must be positive");
        }
        try {
            stopTimeout.toNanos();
        } catch (ArithmeticException ex) {
            throw new IllegalArgumentException("stopTimeout exceeds nanosecond range", ex);
        }
        threadNamePrefix = requireThreadNamePrefix(threadNamePrefix);
    }

    /**
     * 返回默认本地调度参数。
     *
     * @return 默认参数；不可为空、不可变、线程安全。
     */
    public static LocalManagedSchedulerOptions defaults() {
        return new LocalManagedSchedulerOptions(
                DEFAULT_MAX_TASKS,
                DEFAULT_MAX_IN_FLIGHT,
                DEFAULT_STOP_TIMEOUT,
                DEFAULT_THREAD_NAME_PREFIX);
    }

    private static String requireThreadNamePrefix(final String value) {
        String current = Objects.requireNonNull(value, "threadNamePrefix").trim();
        if (current.isEmpty()) {
            throw new IllegalArgumentException("threadNamePrefix must not be blank");
        }
        if (current.codePoints().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("threadNamePrefix must not contain control characters");
        }
        return current;
    }
}
