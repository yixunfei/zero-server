package group.zn.zero.ownership;

import java.time.Instant;
import java.util.Optional;

/** 迁移状态持久化端口；实现可替换为数据库或协调服务。 */
public interface MigrationStore {
    /** 创建迁移；相同 id 重试必须返回同一记录。 */
    MigrationRecord create(MigrationRecord record);

    /** 查询迁移记录。 */
    Optional<MigrationRecord> find(MigrationId id);

    /**
     * 在 FROZEN/CHECKPOINTED 阶段 CAS 更新已校验的快照元数据；不会修改快照版本。
     * 调用方必须完成实际历史补全后才声明 historyComplete。线程安全；冲突或终态抛 OwnershipException。
     */
    MigrationRecord updateCheckpoint(MigrationId id, StateVersion expectedVersion,
            MigrationCheckpointMetadata expectedMetadata, MigrationCheckpointMetadata metadata, Instant now);

    /** 推进状态；非法跳转必须拒绝，重复状态转换必须幂等。 */
    MigrationRecord transition(MigrationId id, MigrationState next, StateVersion checkpointVersion, Instant now);
}
