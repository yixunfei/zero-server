package group.zn.zero.ownership;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import java.util.Objects;

/** ownership、fencing 或迁移状态不满足时抛出的确定性异常。 */
public final class OwnershipException extends RuntimeException {
    /** 对外稳定错误码。 */
    private final ErrorCode errorCode;

    /** 使用系统错误码构造错误，兼容已有调用点。 */
    public OwnershipException(final String message) {
        this(SystemErrorCode.SYSTEM_ERROR, message, null);
    }

    /** 使用指定错误码构造错误。 */
    public OwnershipException(final ErrorCode errorCode, final String message) {
        this(errorCode, message, null);
    }

    /** 使用指定错误码和根因构造错误。 */
    public OwnershipException(final ErrorCode errorCode, final String message, final Throwable cause) {
        super(Objects.requireNonNull(message, "message"), cause);
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode");
    }

    /** 返回对外错误码。 */
    public ErrorCode errorCode() {
        return errorCode;
    }
}
