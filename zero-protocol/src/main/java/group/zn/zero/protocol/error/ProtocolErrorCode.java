package group.zn.zero.protocol.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 协议模块错误码。
 *
 * @author zn
 */
public enum ProtocolErrorCode implements ErrorCode {

    /**
     * 协议定义非法。
     */
    INVALID_PROTOCOL_DEFINITION("ZERO-PROTOCOL-INVALID-DEFINITION", "invalid protocol definition"),

    /**
     * 协议 ID 冲突。
     */
    PROTOCOL_ID_CONFLICT("ZERO-PROTOCOL-ID-CONFLICT", "protocol id conflict"),

    /**
     * 协议名称冲突。
     */
    PROTOCOL_NAME_CONFLICT("ZERO-PROTOCOL-NAME-CONFLICT", "protocol name conflict"),

    /**
     * 协议不存在。
     */
    PROTOCOL_NOT_FOUND("ZERO-PROTOCOL-NOT-FOUND", "protocol not found"),

    /**
     * 编码失败。
     */
    ENCODE_FAILED("ZERO-PROTOCOL-ENCODE-FAILED", "protocol encode failed"),

    /**
     * 解码失败。
     */
    DECODE_FAILED("ZERO-PROTOCOL-DECODE-FAILED", "protocol decode failed"),

    /**
     * 协议帧非法。
     */
    INVALID_FRAME("ZERO-PROTOCOL-INVALID-FRAME", "invalid protocol frame");

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    ProtocolErrorCode(final String code, final String message) {
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 协议错误分类；不可为空；线程安全。
     */
    @Override
    public ErrorCategory category() {
        return ErrorCategory.PROTOCOL;
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
