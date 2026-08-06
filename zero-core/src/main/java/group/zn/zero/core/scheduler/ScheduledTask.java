package group.zn.zero.core.scheduler;

import java.util.concurrent.CompletionStage;

/**
 * 受管定时任务异步业务动作。
 *
 * <p>框架会在受管且非内联的业务执行器中调用本接口。返回的完成信号用于确定真实业务终态、
 * fixed-delay 下一次起点和在途预算释放时刻。实现不得返回空，也不得直接修改其他 Actor 绑定状态。
 *
 * @author zn
 */
@FunctionalInterface
public interface ScheduledTask {

    /**
     * 执行一次定时业务动作。
     *
     * @param context 本次执行上下文；不可为空、不可变、线程安全。
     * @return 异步完成信号；不可为空；完成线程由业务异步链决定。
     * @throws RuntimeException 当任务在返回完成信号前同步失败时抛出；框架会绑定 ErrorCode。
     */
    CompletionStage<Void> execute(ScheduledTaskContext context);
}
