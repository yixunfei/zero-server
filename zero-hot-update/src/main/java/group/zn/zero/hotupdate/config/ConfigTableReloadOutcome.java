package group.zn.zero.hotupdate.config;

import java.util.Objects;

/**
 * 单表同步加载并发布后的内部结果。
 *
 * @param status 重载状态。
 * @param previousVersion 操作前版本。
 * @param currentVersion 操作后版本。
 * @param checksum 当前快照摘要。
 * @param rowCount 当前快照行数。
 * @author zn
 */
record ConfigTableReloadOutcome(
        ConfigReloadStatus status,
        long previousVersion,
        long currentVersion,
        String checksum,
        int rowCount) {

    /**
     * 内部结果标准化构造器。
     */
    ConfigTableReloadOutcome {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(checksum, "checksum");
    }
}
