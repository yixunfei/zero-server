package group.zn.zero.ownership;

/** 迁移恢复状态；状态按单向流程推进，ABORTED 为终态。 */
public enum MigrationState {
    REQUESTED,
    FROZEN,
    CHECKPOINTED,
    COMMITTED,
    ACTIVATED,
    COMPLETED,
    ABORTED
}
