package group.zn.zero.data.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 数据模块错误码。
 *
 * @author zn
 */
public enum DataErrorCode implements ErrorCode {

    /**
     * 仓库未找到。
     */
    REPOSITORY_NOT_FOUND(ErrorCategory.DATA_ACCESS, "ZERO-DATA-REPOSITORY-NOT-FOUND", "repository not found"),

    /**
     * 读取失败。
     */
    READ_FAILED(ErrorCategory.DATA_ACCESS, "ZERO-DATA-READ-FAILED", "data read failed"),

    /**
     * 写入失败。
     */
    WRITE_FAILED(ErrorCategory.DATA_ACCESS, "ZERO-DATA-WRITE-FAILED", "data write failed"),

    /**
     * 删除失败。
     */
    DELETE_FAILED(ErrorCategory.DATA_ACCESS, "ZERO-DATA-DELETE-FAILED", "data delete failed"),

    /**
     * 版本冲突。
     */
    VERSION_CONFLICT(ErrorCategory.DATA_ACCESS, "ZERO-DATA-VERSION-CONFLICT", "data version conflict"),

    /**
     * 数据映射非法。
     */
    MAPPING_INVALID(ErrorCategory.DATA_ACCESS, "ZERO-DATA-MAPPING-INVALID", "data mapping is invalid"),

    /**
     * 后端不可用。
     */
    BACKEND_UNAVAILABLE(ErrorCategory.SYSTEM, "ZERO-DATA-BACKEND-UNAVAILABLE", "data backend is unavailable"),

    /**
     * 数据实体非法。
     */
    INVALID_ENTITY(ErrorCategory.CLIENT_REQUEST, "ZERO-DATA-INVALID-ENTITY", "invalid data entity"),

    /**
     * 持久化目标未找到。
     */
    PERSISTENCE_TARGET_NOT_FOUND(
            ErrorCategory.DATA_ACCESS,
            "ZERO-DATA-PERSISTENCE-TARGET-NOT-FOUND",
            "persistence target not found"),

    /**
     * 持久化 flush 失败。
     */
    PERSISTENCE_FLUSH_FAILED(
            ErrorCategory.DATA_ACCESS,
            "ZERO-DATA-PERSISTENCE-FLUSH-FAILED",
            "persistence flush failed");

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    DataErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return category;
    }

    /**
     * 返回对外稳定错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回默认错误说明。
     *
     * @return 默认错误说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
