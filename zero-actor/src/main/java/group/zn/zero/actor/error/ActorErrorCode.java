package group.zn.zero.actor.error;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/**
 * Actor 模块错误码。
 *
 * @author zn
 */
public enum ActorErrorCode implements ErrorCode {

    /**
     * Actor 路由不存在。
     */
    ROUTE_NOT_FOUND(ErrorCategory.SERVER_CALL, "ZERO-ACTOR-ROUTE-NOT-FOUND", "actor route not found"),

    /**
     * 远程 Actor 网关不可用。
     */
    REMOTE_GATEWAY_UNAVAILABLE(
            ErrorCategory.SYSTEM,
            "ZERO-ACTOR-REMOTE-GATEWAY-UNAVAILABLE",
            "remote actor gateway is unavailable"),

    /**
     * 远程 Actor 投递失败。
     */
    REMOTE_DISPATCH_FAILED(
            ErrorCategory.SERVER_CALL,
            "ZERO-ACTOR-REMOTE-DISPATCH-FAILED",
            "remote actor dispatch failed"),

    /**
     * 远程 Actor 消息已过期。
     */
    REMOTE_MESSAGE_EXPIRED(
            ErrorCategory.SERVER_CALL,
            "ZERO-ACTOR-REMOTE-MESSAGE-EXPIRED",
            "remote actor message expired");

    /**
     * 错误分类。
     */
    private final ErrorCategory category;

    /**
     * 对外稳定错误码。
     */
    private final String code;

    /**
     * 默认错误说明。
     */
    private final String message;

    ActorErrorCode(final ErrorCategory category, final String code, final String message) {
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
