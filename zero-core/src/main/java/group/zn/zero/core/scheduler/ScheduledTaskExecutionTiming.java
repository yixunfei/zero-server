package group.zn.zero.core.scheduler;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/**
 * 单次受管任务执行的计划与实际开始时间。
 *
 * @param scheduledAt 本次 occurrence 的计划观测时间。
 * @param startedAt 用户任务实际开始的观测时间。
 * @author zn
 */
public record ScheduledTaskExecutionTiming(
        Instant scheduledAt,
        Instant startedAt) {

    /**
     * 标准化执行时序。
     *
     * @throws NullPointerException 当任一时间为空时抛出。
     */
    public ScheduledTaskExecutionTiming {
        scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
        startedAt = Objects.requireNonNull(startedAt, "startedAt");
    }

    /**
     * 返回从计划时间到实际开始时间的非负观测延迟。
     *
     * <p>墙钟回拨时返回零，避免把负值写入日志或指标；该值仅用于观测，不参与 monotonic 调度。</p>
     *
     * @return 非负延迟；不可为空、不可变、线程安全。
     */
    public Duration startDelay() {
        Duration delay = Duration.between(scheduledAt, startedAt);
        return delay.isNegative() ? Duration.ZERO : delay;
    }
}
