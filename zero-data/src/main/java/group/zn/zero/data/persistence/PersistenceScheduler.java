package group.zn.zero.data.persistence;

import java.time.Duration;

/**
 * 持久化调度器 SPI。
 *
 * <p>该接口只定义定时 flush 的装配边界，不要求实现直接创建线程池。
 * 生产实现应接入框架统一线程管理。
 *
 * @author zn
 */
public interface PersistenceScheduler {

    /**
     * 按固定间隔调度任务。
     *
     * @param taskName 任务名称；不可为空。
     * @param interval 调度间隔；不可为空且必须大于 0。
     * @param task 任务逻辑；不可为空。
     * @return 调度句柄；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 调度失败时抛出，必须绑定 ErrorCode。
     */
    PersistenceScheduleHandle scheduleAtFixedRate(String taskName, Duration interval, Runnable task);
}
