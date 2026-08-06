package group.zn.zero.core.error;

/**
 * 系统级错误码。
 *
 * @author zn
 */
public enum SystemErrorCode implements ErrorCode {

    /**
     * 操作成功。
     */
    OK(ErrorCategory.SYSTEM, "ZERO-OK", "success"),

    /**
     * 未分类系统错误。
     */
    SYSTEM_ERROR(ErrorCategory.SYSTEM, "ZERO-SYSTEM-ERROR", "system error"),

    /**
     * 参数非法。
     */
    INVALID_ARGUMENT(ErrorCategory.CLIENT_REQUEST, "ZERO-INVALID-ARGUMENT", "invalid argument"),

    /**
     * 组件尚未实现。
     */
    NOT_IMPLEMENTED(ErrorCategory.SYSTEM, "ZERO-NOT-IMPLEMENTED", "not implemented");

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

    SystemErrorCode(final ErrorCategory category, final String code, final String message) {
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
     * 返回对外稳定的错误码。
     *
     * @return 错误码字符串；不可为空；线程安全。
     */
    @Override
    public String code() {
        return code;
    }

    /**
     * 返回错误码默认说明。
     *
     * @return 默认错误说明；不可为空；线程安全。
     */
    @Override
    public String message() {
        return message;
    }
}
