package group.zn.zero.actor.scheduler;

import java.time.Duration;

/**
 * 不可变调度预算。计数包括排队、执行中和异步挂起；取消返回 Future 不提前归还许可。
 * @param maxPendingPerLane 每 Lane 最大未完成消息数。
 * @param maxPendingTotal 调度器最大未完成消息数。
 * @param maxMessagesPerDrain 每次执行器任务最多处理的消息数。
 * @param executionTimeout 单条消息实际执行预算；零表示仅禁用超时观察。
 * @author zn
 */
public record ActorSchedulerConfig(int maxPendingPerLane, int maxPendingTotal, int maxMessagesPerDrain,
        Duration executionTimeout) {
    /** 保留三参数配置入口，使用默认 30 秒执行预算。 */
    public ActorSchedulerConfig(final int maxPendingPerLane, final int maxPendingTotal,
            final int maxMessagesPerDrain) {
        this(maxPendingPerLane, maxPendingTotal, maxMessagesPerDrain, Duration.ofSeconds(30));
    }
    /** 校验正数预算；参数无效时抛出 IllegalArgumentException，线程安全。 */
    public ActorSchedulerConfig {
        if (maxPendingPerLane <= 0 || maxPendingTotal <= 0 || maxMessagesPerDrain <= 0) {
            throw new IllegalArgumentException("actor budgets must be positive");
        }
        if (executionTimeout == null || executionTimeout.isNegative()) {
            throw new IllegalArgumentException("executionTimeout must not be negative");
        }
        executionTimeout.toNanos();
    }
    /** @return 不可变默认预算：每 Lane 4096、全局 65536、每批 64；线程安全。 */
    public static ActorSchedulerConfig defaults() {
        return new ActorSchedulerConfig(4096, 65536, 64, Duration.ofSeconds(30));
    }
}
