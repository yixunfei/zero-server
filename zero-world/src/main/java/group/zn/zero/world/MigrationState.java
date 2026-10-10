package group.zn.zero.world;
/** 迁移阶段；目标提交后必须释放源令牌才能再次迁移。 @author zn */
public enum MigrationState {
    SOURCE_LOCKED,
    TARGET_PREPARED,
    TARGET_COMMITTED,
    COMPLETED,
    ROLLBACK_SOURCE,
    SOURCE_UNLOCKED,
    FAILED
}
