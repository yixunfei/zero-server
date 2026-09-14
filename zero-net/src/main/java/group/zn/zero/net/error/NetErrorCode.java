package group.zn.zero.net.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * 网络模块错误码。
 *
 * @author zn
 */
public enum NetErrorCode implements ErrorCode {

    /**
     * 网络服务启动失败。
     */
    START_FAILED("ZERO-NET-START-FAILED", "net server start failed"),

    /**
     * 网络服务停止失败。
     */
    STOP_FAILED("ZERO-NET-STOP-FAILED", "net server stop failed"),

    /**
     * 网络消息发送失败。
     */
    SEND_FAILED("ZERO-NET-SEND-FAILED", "net message send failed"),

    /**
     * 网络消息类型非法。
     */
    INVALID_MESSAGE("ZERO-NET-INVALID-MESSAGE", "invalid net message"),

    /**
     * 网络处理器执行失败。
     */
    HANDLER_FAILED("ZERO-NET-HANDLER-FAILED", "net handler failed"),

    /**
     * 生产连接握手超时。
     */
    HANDSHAKE_TIMEOUT(ErrorCategory.PROTOCOL,
            "ZERO-NET-HANDSHAKE-TIMEOUT", "network handshake timed out"),

    /**
     * 生产连接握手被拒绝。
     */
    HANDSHAKE_REJECTED(ErrorCategory.PROTOCOL,
            "ZERO-NET-HANDSHAKE-REJECTED", "network handshake rejected"),

    /**
     * 客户端协议版本不兼容。
     */
    PROTOCOL_VERSION_UNSUPPORTED(ErrorCategory.PROTOCOL,
            "ZERO-NET-PROTOCOL-VERSION-UNSUPPORTED", "network protocol version unsupported"),

    /**
     * 生产连接鉴权超时。
     */
    AUTHENTICATION_TIMEOUT(ErrorCategory.PERMISSION,
            "ZERO-NET-AUTHENTICATION-TIMEOUT", "network authentication timed out"),

    /**
     * 生产连接鉴权被拒绝。
     */
    AUTHENTICATION_REJECTED(ErrorCategory.PERMISSION,
            "ZERO-NET-AUTHENTICATION-REJECTED", "network authentication rejected"),

    /**
     * 生产连接心跳超时。
     */
    HEARTBEAT_TIMEOUT(ErrorCategory.PROTOCOL,
            "ZERO-NET-HEARTBEAT-TIMEOUT", "network heartbeat timed out"),

    /**
     * 单连接入站 frame 预算耗尽。
     */
    INBOUND_OVERFLOW("ZERO-NET-INBOUND-OVERFLOW", "network inbound frame budget exceeded"),

    /**
     * 连接或 frame 被限流。
     */
    RATE_LIMITED(ErrorCategory.CLIENT_REQUEST,
            "ZERO-NET-RATE-LIMITED", "network request rate limited"),

    /** 认证主体缺失。 */
    UNAUTHENTICATED(ErrorCategory.PERMISSION, "ZERO-NET-UNAUTHENTICATED", "network authentication required"),

    /** 认证凭据已经过期。 */
    AUTHENTICATION_EXPIRED(ErrorCategory.PERMISSION, "ZERO-NET-AUTHENTICATION-EXPIRED", "network authentication expired"),

    /** 请求被识别为重放。 */
    REPLAY_DETECTED(ErrorCategory.PERMISSION, "ZERO-NET-REPLAY-DETECTED", "network replay detected"),

    /** TLS 是必需的但连接未使用 TLS。 */
    TLS_REQUIRED(ErrorCategory.PERMISSION, "ZERO-NET-TLS-REQUIRED", "TLS is required"),

    /** TLS 握手或证书材料失败。 */
    TLS_HANDSHAKE_FAILED(ErrorCategory.PERMISSION, "ZERO-NET-TLS-HANDSHAKE-FAILED", "TLS handshake failed"),

    /** 请求授权被拒绝。 */
    AUTHORIZATION_DENIED(ErrorCategory.PERMISSION, "ZERO-NET-AUTHORIZATION-DENIED", "network authorization denied"),

    /** 来源地址命中黑名单。 */
    BLACKLISTED(ErrorCategory.PERMISSION, "ZERO-NET-BLACKLISTED", "network source is blocked"),

    /** 连接建立过慢。 */
    SLOW_CONNECTION(ErrorCategory.CLIENT_REQUEST, "ZERO-NET-SLOW-CONNECTION", "network connection is too slow"),

    /**
     * 玩家重连协调失败。
     */
    RECONNECT_FAILED("ZERO-NET-RECONNECT-FAILED", "network reconnect coordination failed"),

    /**
     * 连接生命周期状态非法。
     */
    INVALID_LIFECYCLE_STATE("ZERO-NET-INVALID-LIFECYCLE-STATE", "invalid network lifecycle state"),

    /**
     * 生命周期 observer 执行失败。
     */
    OBSERVER_FAILED("ZERO-NET-OBSERVER-FAILED", "network lifecycle observer failed"),

    /**
     * KCP 服务器尚未接入。
     */
    KCP_NOT_IMPLEMENTED("ZERO-NET-KCP-NOT-IMPLEMENTED", "kcp server is not implemented");

    /**
     * 错误码。
     */
    private final String code;

    /**
     * 默认说明。
     */
    private final String message;

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    NetErrorCode(final String code, final String message) {
        this(ErrorCategory.SYSTEM, code, message);
    }

    NetErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    /**
     * 返回错误分类。
     *
     * @return 系统错误分类；不可为空；线程安全。
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
