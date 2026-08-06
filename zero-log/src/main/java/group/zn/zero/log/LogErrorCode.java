package group.zn.zero.log;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 日志模块稳定错误码。
 *
 * @author zn
 */
public enum LogErrorCode implements ErrorCode {

    /**
     * 日志记录结构、预算或结果组合非法。
     */
    INVALID_RECORD("ZERO-LOG-INVALID-RECORD", "log record is invalid"),

    /**
     * 敏感字段或凭据内容被安全门拒绝。
     */
    SENSITIVE_FIELD_REJECTED(
            "ZERO-LOG-SENSITIVE-FIELD-REJECTED",
            "sensitive log field was rejected"),

    /**
     * 日志处理器执行失败。
     */
    PROCESSOR_FAILED("ZERO-LOG-PROCESSOR-FAILED", "log processor failed"),

    /**
     * 终端日志 sink 执行失败。
     */
    SINK_FAILED("ZERO-LOG-SINK-FAILED", "log sink failed");

    /**
     * 对外稳定错误码。
     */
    private final String code;

    /**
     * 默认错误说明。
     */
    private final String message;

    LogErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回系统错误分类。
     *
     * @return {@link ErrorCategory#SYSTEM}；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return ErrorCategory.SYSTEM;
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
     * @return 默认说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
