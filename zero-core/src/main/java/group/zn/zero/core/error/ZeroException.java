package group.zn.zero.core.error;

import java.util.Objects;

/**
 * zeroServer 统一运行时异常。
 *
 * @author zn
 */
public class ZeroException extends RuntimeException {

    /**
     * 错误码。
     */
    private final ErrorCode errorCode;

    /**
     * 创建异常。
     *
     * @param errorCode 错误码；不可为空。
     * @param message 异常说明；不可为空，可覆盖错误码默认说明。
     * @throws NullPointerException 当错误码或异常说明为空时抛出。
     */
    public ZeroException(final ErrorCode errorCode, final String message) {
        super(Objects.requireNonNull(message, "message"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * 创建异常。
     *
     * @param errorCode 错误码；不可为空。
     * @param message 异常说明；不可为空，可覆盖错误码默认说明。
     * @param cause 原始异常；可为空。
     * @throws NullPointerException 当错误码或异常说明为空时抛出。
     */
    public ZeroException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /**
     * 根据错误码和默认说明创建异常。
     *
     * @param errorCode 错误码；不可为空。
     * @return zeroServer 统一运行时异常；不可为空；线程安全。
     */
    public static ZeroException of(final ErrorCode errorCode) {
        ErrorCode checkedErrorCode = Objects.requireNonNull(errorCode, "errorCode");
        return new ZeroException(checkedErrorCode, Objects.requireNonNull(checkedErrorCode.message(), "message"));
    }

    /**
     * 根据错误码、说明和原始异常创建异常。
     *
     * @param errorCode 错误码；不可为空。
     * @param message 异常说明；不可为空。
     * @param cause 原始异常；可为空。
     * @return zeroServer 统一运行时异常；不可为空；线程安全。
     */
    public static ZeroException of(final ErrorCode errorCode, final String message, final Throwable cause) {
        return new ZeroException(errorCode, message, cause);
    }

    /**
     * 返回错误分类。
     *
     * @return 错误分类；不可为空；线程安全。
     */
    public ErrorCategory category() {
        return errorCode.category();
    }

    /**
     * 返回错误码字符串。
     *
     * @return 错误码；不可为空；线程安全。
     */
    public String code() {
        return errorCode.code();
    }

    /**
     * 返回错误码。
     *
     * @return 错误码；不可为空；线程安全。
     */
    public ErrorCode errorCode() {
        return errorCode;
    }

    /**
     * 返回异常说明。
     *
     * @return 异常说明；不可为空；线程安全。
     */
    public String message() {
        return getMessage();
    }

    /**
     * 返回异常字符串。
     *
     * @return 异常字符串；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "ZeroException{"
                + "category=" + category()
                + ", code='" + code() + '\''
                + ", message='" + getMessage() + '\''
                + '}';
    }
}
