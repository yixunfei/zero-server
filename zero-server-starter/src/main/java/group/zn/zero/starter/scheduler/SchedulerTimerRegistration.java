package group.zn.zero.starter.scheduler;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ScheduledFuture;

/**
 * 单次 timer 登记，先发布身份再接收 future，避免零延迟回调覆盖后续重排。
 * 所有可变状态由本对象监视器保护；不执行用户任务，不等待 timer 完成。
 * @author zn
 */
final class SchedulerTimerRegistration {
    /** 本次触发的观测时间，与登记身份保持一致。 */
    private final Instant scheduledAt;
    /** schedule 返回的唯一 future，取消可以先于该字段赋值。 */
    private ScheduledFuture<?> future;
    /** 取消已经请求，迟到的 future 必须立即取消且不打断运行中回调。 */
    private boolean cancelled;

    SchedulerTimerRegistration(final Instant scheduledAt) {
        this.scheduledAt = Objects.requireNonNull(scheduledAt, "scheduledAt");
    }

    Instant scheduledAt() {
        return scheduledAt;
    }

    synchronized void attach(final ScheduledFuture<?> scheduled) {
        future = Objects.requireNonNull(scheduled, "scheduled");
        if (cancelled) future.cancel(false);
    }

    synchronized void cancel() {
        cancelled = true;
        if (future != null) future.cancel(false);
    }
}
