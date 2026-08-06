package group.zn.zero.hotupdate.config;

import group.zn.zero.core.error.ErrorCode;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 配置表加载或热重载结果。
 *
 * @param tableName 稳定表名。
 * @param sourceName 脱敏来源名称，只包含文件名，不包含完整路径。
 * @param status 结果状态。
 * @param previousVersion 操作前版本；初始加载为 0。
 * @param currentVersion 操作后仍可读取的版本；未加载时为 0。
 * @param checksum 当前可读取版本的 SHA-256；未加载时为空字符串。
 * @param rowCount 当前可读取版本行数。
 * @param elapsed 操作耗时。
 * @param completedAt 操作完成时间。
 * @param errorCode 失败 ErrorCode；成功或 no-op 时为空。
 * @author zn
 */
public record ConfigReloadResult(
        String tableName,
        String sourceName,
        ConfigReloadStatus status,
        long previousVersion,
        long currentVersion,
        String checksum,
        int rowCount,
        Duration elapsed,
        Instant completedAt,
        ErrorCode errorCode) {

    /**
     * 重载结果标准化构造器。
     *
     * @throws NullPointerException 当必要字段为空时抛出。
     * @throws IllegalArgumentException 当版本、行数或 ErrorCode 与状态不一致时抛出。
     */
    public ConfigReloadResult {
        tableName = Objects.requireNonNull(tableName, "tableName");
        sourceName = Objects.requireNonNull(sourceName, "sourceName");
        status = Objects.requireNonNull(status, "status");
        checksum = Objects.requireNonNull(checksum, "checksum");
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        completedAt = Objects.requireNonNull(completedAt, "completedAt");
        if (previousVersion < 0 || currentVersion < 0 || rowCount < 0 || elapsed.isNegative()) {
            throw new IllegalArgumentException("versions, rowCount and elapsed must not be negative");
        }
        if (status == ConfigReloadStatus.REJECTED && errorCode == null) {
            throw new IllegalArgumentException("rejected result must bind errorCode");
        }
        if (status != ConfigReloadStatus.REJECTED && errorCode != null) {
            throw new IllegalArgumentException("successful result must not bind errorCode");
        }
    }

    /**
     * 返回操作是否未被拒绝。
     *
     * @return `LOADED` 或 `NO_CHANGE` 时返回 true；线程安全。
     */
    public boolean accepted() {
        return status != ConfigReloadStatus.REJECTED;
    }

    /**
     * 返回可选错误码。
     *
     * @return 失败 ErrorCode；成功时为空；线程安全。
     */
    public Optional<ErrorCode> optionalErrorCode() {
        return Optional.ofNullable(errorCode);
    }
}
