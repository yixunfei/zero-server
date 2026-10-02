package group.zn.zero.ownership;

import java.time.Instant;
import java.util.Optional;

/** 迁移状态持久化端口；实现可替换为数据库或协调服务。 */
public interface MigrationStore {
    /** 创建迁移；相同 id 重试必须返回同一记录。 */
    MigrationRecord create(MigrationRecord record);

    /** 查询迁移记录。 */
    Optional<MigrationRecord> find(MigrationId id);

    /** 推进状态；非法跳转必须拒绝，重复状态转换必须幂等。 */
    MigrationRecord transition(MigrationId id, MigrationState next, StateVersion checkpointVersion, Instant now);
}
