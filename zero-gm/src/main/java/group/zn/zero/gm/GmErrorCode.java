package group.zn.zero.gm;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * GM 模块错误码。
 *
 * @author zn
 */
public enum GmErrorCode implements ErrorCode {

    /**
     * GM 指令 DSL 为空。
     */
    COMMAND_DSL_EMPTY(ErrorCategory.CLIENT_REQUEST, "ZERO-GM-COMMAND-DSL-EMPTY", "gm command dsl is empty"),

    /**
     * GM 指令 DSL 格式非法。
     */
    COMMAND_DSL_INVALID(ErrorCategory.CLIENT_REQUEST, "ZERO-GM-COMMAND-DSL-INVALID", "gm command dsl is invalid"),

    /**
     * GM 指令未注册。
     */
    COMMAND_NOT_FOUND(ErrorCategory.CLIENT_REQUEST, "ZERO-GM-COMMAND-NOT-FOUND", "gm command not found"),

    /**
     * GM 指令参数不匹配。
     */
    COMMAND_ARGUMENT_MISMATCH(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-GM-COMMAND-ARGUMENT-MISMATCH",
            "gm command argument mismatch"),

    /**
     * GM 指令重复注册。
     */
    COMMAND_ALREADY_REGISTERED(
            ErrorCategory.SYSTEM,
            "ZERO-GM-COMMAND-ALREADY-REGISTERED",
            "gm command already registered"),

    /**
     * GM handler 返回空结果或结果字段组合非法。
     */
    COMMAND_RESULT_INVALID(
            ErrorCategory.SYSTEM,
            "ZERO-GM-COMMAND-RESULT-INVALID",
            "gm command result is invalid"),

    /**
     * GM 指令在没有更具体领域码时被无副作用拒绝。
     */
    COMMAND_REJECTED(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-GM-COMMAND-REJECTED",
            "gm command was rejected"),

    /**
     * 非 ZeroException 的 GM handler 未知失败。
     */
    HANDLER_FAILED(
            ErrorCategory.SYSTEM,
            "ZERO-GM-HANDLER-FAILED",
            "gm command handler failed"),

    OPERATION_AUTHORIZATION_DENIED(
            ErrorCategory.CLIENT_REQUEST,
            "ZERO-GM-OPERATION-AUTHORIZATION-DENIED",
            "gm operation authorization denied"),

    /**
     * GM 审计 hook 执行失败。
     */
    AUDIT_HOOK_FAILED(ErrorCategory.SYSTEM, "ZERO-GM-AUDIT-HOOK-FAILED", "gm audit hook failed");

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

    GmErrorCode(final ErrorCategory category, final String code, final String message) {
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
