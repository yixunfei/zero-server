package group.zn.zero.core.error;

/**
 * 框架统一错误码。
 *
 * @author zn
 */
public interface ErrorCode {

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    ErrorCategory category();

    /**
     * 返回对外稳定的错误码。
     *
     * @return 错误码字符串；不可为空；线程安全。
     */
    String code();

    /**
     * 返回错误码默认说明。
     *
     * @return 默认错误说明；不可为空；线程安全。
     */
    String message();
}
