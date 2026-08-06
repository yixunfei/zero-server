package group.zn.zero.core.scheduler;

import group.zn.zero.core.lifecycle.Lifecycle;
import java.time.Duration;
import java.util.List;

/**
 * 框架受管定时任务调度器。
 *
 * <p>实现必须统一管理 timer 生命周期、任务容量、取消竞争、异步完成和观测，不得向业务暴露
 * 原始 Future、executor 或线程。玩家、场景和实体状态修改必须继续通过 Actor 消息完成。
 *
 * @author zn
 */
public interface ManagedScheduler extends Lifecycle {

    /**
     * 安排一次性延迟任务。
     *
     * @param definition 任务定义；不可为空。
     * @param delay 延迟；不可为空、不得为负数。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当调度器未运行、参数非法、容量不足或 timer 拒绝时抛出。
     */
    ScheduledTaskHandle scheduleOnce(
            ScheduledTaskDefinition definition,
            Duration delay,
            ScheduledTask task);

    /**
     * 安排固定延迟周期任务。
     *
     * <p>下一次延迟从前一次返回 CompletionStage 的终态时刻开始计算。
     *
     * @param definition 任务定义；不可为空。
     * @param initialDelay 首次延迟；不可为空、不得为负数。
     * @param delay 完成后的固定延迟；不可为空且必须大于零。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当调度器未运行、参数非法、容量不足或 timer 拒绝时抛出。
     */
    ScheduledTaskHandle scheduleWithFixedDelay(
            ScheduledTaskDefinition definition,
            Duration initialDelay,
            Duration delay,
            ScheduledTask task);

    /**
     * 安排固定频率周期任务。
     *
     * <p>实现按 monotonic deadline 自行重排一次性 timer；运行中、容量不足或已经错过的节拍会跳过，
     * 不排队、不补跑、不追赶。
     *
     * @param definition 任务定义；不可为空。
     * @param initialDelay 首次延迟；不可为空、不得为负数。
     * @param period 固定周期；不可为空且必须大于零。
     * @param task 异步任务；不可为空。
     * @return 任务句柄；不可为空、线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当调度器未运行、参数非法、容量不足或 timer 拒绝时抛出。
     */
    ScheduledTaskHandle scheduleAtFixedRate(
            ScheduledTaskDefinition definition,
            Duration initialDelay,
            Duration period,
            ScheduledTask task);

    /**
     * 返回活动任务快照。
     *
     * @return 不可变、有序、可能为空、线程安全的活动任务快照列表。
     */
    List<ScheduledTaskSnapshot> snapshots();
}
