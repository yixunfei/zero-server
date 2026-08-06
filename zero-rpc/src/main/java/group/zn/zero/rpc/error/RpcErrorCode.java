package group.zn.zero.rpc.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * RPC 模块错误码。
 *
 * @author zn
 */
public enum RpcErrorCode implements ErrorCode {

    /**
     * RPC 请求非法。
     */
    INVALID_REQUEST(ErrorCategory.CLIENT_REQUEST, "ZERO-RPC-INVALID-REQUEST", "invalid rpc request"),

    /**
     * RPC 请求超时。
     */
    REQUEST_TIMEOUT(ErrorCategory.SERVER_CALL, "ZERO-RPC-REQUEST-TIMEOUT", "rpc request timed out"),

    /**
     * RPC 传输不可用。
     */
    TRANSPORT_UNAVAILABLE(ErrorCategory.SYSTEM, "ZERO-RPC-TRANSPORT-UNAVAILABLE", "rpc transport is unavailable"),

    /**
     * 找不到 RPC 服务或方法。
     */
    SERVICE_NOT_FOUND(ErrorCategory.SERVER_CALL, "ZERO-RPC-SERVICE-NOT-FOUND", "rpc service or method not found"),

    /**
     * RPC 处理器执行失败。
     */
    HANDLER_FAILED(ErrorCategory.SYSTEM, "ZERO-RPC-HANDLER-FAILED", "rpc handler failed"),

    /**
     * RPC 契约非法。
     */
    CONTRACT_INVALID(ErrorCategory.CLIENT_REQUEST, "ZERO-RPC-CONTRACT-INVALID", "rpc contract is invalid"),

    /**
     * RPC codec 缺失。
     */
    CODEC_NOT_FOUND(ErrorCategory.SYSTEM, "ZERO-RPC-CODEC-NOT-FOUND", "rpc codec not found"),

    /**
     * RPC codec 执行失败。
     */
    CODEC_FAILED(ErrorCategory.SYSTEM, "ZERO-RPC-CODEC-FAILED", "rpc codec failed"),

    /**
     * RPC 接口调用失败。
     */
    INVOCATION_FAILED(ErrorCategory.SYSTEM, "ZERO-RPC-INVOCATION-FAILED", "rpc invocation failed");

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

    RpcErrorCode(final ErrorCategory category, final String code, final String message) {
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
