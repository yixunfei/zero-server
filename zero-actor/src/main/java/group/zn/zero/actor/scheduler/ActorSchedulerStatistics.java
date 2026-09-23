package group.zn.zero.actor.scheduler;

/**
 * 固定维度的调度器观察快照，不含玩家/Lane ID 标签；各字段不是线性一致的同一时刻。
 * @param pending 排队、执行和异步挂起总数。
 * @param active 执行及异步挂起数。
 * @param completed 已执行并结束的消息数，含 handler 失败；不含排队阶段拒绝。
 * @param rejected 准入拒绝、执行器拒绝或关闭时排队失败的消息数。
 * @param totalQueueWaitNanos 已开始消息的累计等待纳秒数。
 * @param maxQueueWaitNanos 已开始消息的最大等待纳秒数。
 * @author zn
 */
public record ActorSchedulerStatistics(int pending, int active, long completed, long rejected,
        long totalQueueWaitNanos, long maxQueueWaitNanos) { }
