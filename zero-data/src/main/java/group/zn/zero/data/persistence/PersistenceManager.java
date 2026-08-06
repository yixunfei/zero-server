package group.zn.zero.data.persistence;

import group.zn.zero.data.DataService;
import group.zn.zero.data.model.VersionedEntity;
import java.time.Duration;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/**
 * 统一持久化管理服务。
 *
 * <p>该服务负责登记脏对象、按线程绑定捕获快照、执行手动或定时 flush，并为后续缓存写回、
 * 降级和观测保留统一入口。
 *
 * @author zn
 */
public interface PersistenceManager extends DataService {

    /**
     * 注册持久化目标。
     *
     * @param target 持久化目标；不可为空。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @throws group.zn.zero.core.error.ZeroException 注册失败时抛出，必须绑定 ErrorCode。
     */
    <ID, T extends VersionedEntity<ID>> void registerTarget(PersistenceTarget<ID, T> target);

    /**
     * 登记脏对象。
     *
     * @param targetName 持久化目标名称；不可为空。
     * @param id 对象 ID；不可为空。
     * @param binding 数据对象线程绑定键；不可为空。
     * @param snapshotSupplier 快照供应器；不可为空，必须返回可直接落库的对象快照。
     * @param <ID> 主键类型。
     * @param <T> 实体类型。
     * @throws group.zn.zero.core.error.ZeroException 目标不存在时抛出，必须绑定 ErrorCode。
     */
    <ID, T extends VersionedEntity<ID>> void markDirty(
            String targetName,
            ID id,
            DataThreadBinding binding,
            Supplier<T> snapshotSupplier);

    /**
     * 立即 flush 默认批量数量的脏对象。
     *
     * @return flush 结果；不可为空；线程安全。
     */
    CompletionStage<PersistenceFlushResult> flushNow();

    /**
     * 立即 flush 指定数量的脏对象。
     *
     * @param maxBatchSize 最大批量数量；必须大于 0。
     * @return flush 结果；不可为空；线程安全。
     */
    CompletionStage<PersistenceFlushResult> flushNow(int maxBatchSize);

    /**
     * 注册定时 flush。
     *
     * @param scheduler 持久化调度器；不可为空。
     * @param interval 调度间隔；不可为空且必须大于 0。
     * @return 调度句柄；不可为空；线程安全。
     */
    PersistenceScheduleHandle scheduleFlush(PersistenceScheduler scheduler, Duration interval);

    /**
     * 返回统计快照。
     *
     * @return 统计快照；不可为空；线程安全。
     */
    PersistenceStatistics statistics();
}
