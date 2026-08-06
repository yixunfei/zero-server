package group.zn.zero.hotupdate.config;

import java.util.Objects;

/**
 * 尚未发布的初始配置快照及其发布/回滚动作。
 *
 * @param tableName 稳定表名。
 * @param sourceName 脱敏文件名。
 * @param version 候选版本。
 * @param checksum 候选 SHA-256。
 * @param rowCount 候选行数。
 * @param publisher 原子发布动作。
 * @param rollback 发布后的回滚动作。
 * @author zn
 */
record ConfigReloadCandidate(
        String tableName,
        String sourceName,
        long version,
        String checksum,
        int rowCount,
        Runnable publisher,
        Runnable rollback) {

    /**
     * 候选标准化构造器。
     */
    ConfigReloadCandidate {
        Objects.requireNonNull(tableName, "tableName");
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(checksum, "checksum");
        Objects.requireNonNull(publisher, "publisher");
        Objects.requireNonNull(rollback, "rollback");
    }

    /**
     * 发布候选快照。
     */
    void publish() {
        publisher.run();
    }

    /**
     * 回滚已发布候选快照。
     */
    void rollBack() {
        rollback.run();
    }
}
