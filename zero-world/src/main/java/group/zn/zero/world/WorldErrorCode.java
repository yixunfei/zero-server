package group.zn.zero.world;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 本地世界准入失败；拒绝前不改变实体所有权。 @author zn */
public enum WorldErrorCode implements ErrorCode {
    /** 保留记录满，既有迁移可继续推进和重放。 */
    MIGRATION_CAPACITY_EXCEEDED,
    /** 迁移收据已归档或 ID 低于重放水位。 */
    MIGRATION_RETIRED;

    /** @return 稳定系统分类；线程安全。 */
    @Override public ErrorCategory category() { return ErrorCategory.SYSTEM; }
    /** @return 稳定错误码；线程安全。 */
    @Override public String code() {
        return this == MIGRATION_CAPACITY_EXCEEDED
                ? "ZERO-WORLD-MIGRATION-CAPACITY-EXCEEDED" : "ZERO-WORLD-MIGRATION-RETIRED";
    }
    /** @return 安全说明；线程安全。 */
    @Override public String message() {
        return this == MIGRATION_CAPACITY_EXCEEDED
                ? "world migration receipt capacity exceeded" : "world migration receipt retired";
    }
}
