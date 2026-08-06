package group.zn.zero.core.scheduler;

/**
 * 受管定时任务观察端口。
 *
 * <p>本接口不会在 timer thread 中调用。实现不得直接修改玩家、场景或其他 Actor 绑定状态；
 * 如需触发业务行为，必须投递到对应 Actor。实现异常由本地 scheduler 隔离并绑定 ErrorCode。
 *
 * @author zn
 */
@FunctionalInterface
public interface ScheduledTaskObserver {

    /**
     * 消费一个结构化调度事件。
     *
     * @param event 调度事件；不可为空、不可变、线程安全。
     * @throws RuntimeException 当观测落地失败时抛出；scheduler 会隔离并记录。
     */
    void onEvent(ScheduledTaskEvent event);

    /**
     * 返回无操作 observer。
     *
     * @return 无操作 observer；不可为空、线程安全。
     */
    static ScheduledTaskObserver noOp() {
        return event -> {
            // 显式无操作 observer，用于不需要观测落地的嵌入或测试场景。
        };
    }
}
