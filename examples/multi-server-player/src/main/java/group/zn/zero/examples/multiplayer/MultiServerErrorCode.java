package group.zn.zero.examples.multiplayer;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 示例业务错误分类；不可变、线程安全，不代表框架公共错误码。 @author zn */
public enum MultiServerErrorCode implements ErrorCode {
    /** 请求字段非法。 */
    INVALID_REQUEST(ErrorCategory.CLIENT_REQUEST, "invalid request"),
    /** 账号重复注册。 */
    ACCOUNT_EXISTS(ErrorCategory.CLIENT_REQUEST, "account already registered"),
    /** 角色已存在，不覆盖已提交文档。 */
    CHARACTER_EXISTS(ErrorCategory.CLIENT_REQUEST, "character already exists"),
    /** 教学凭据不匹配。 */
    AUTHENTICATION_FAILED(ErrorCategory.PERMISSION, "account authentication failed"),
    /** 会话已失效或被伪造。 */
    STALE_SESSION(ErrorCategory.PERMISSION, "session is not current"),
    /** 路由表没有可选实例。 */
    NO_LOGIC_SERVER(ErrorCategory.SERVER_CALL, "no logic server registered"),
    /** 违反单线程编排约束。 */
    WRONG_THREAD(ErrorCategory.SYSTEM, "call on the creating command-line thread"),
    /** 服务已关闭或提交后的权威数据缺失。 */
    INVALID_STATE(ErrorCategory.SYSTEM, "invalid example service state");

    /** 错误类别。 */
    private final ErrorCategory category;
    /** 默认诊断信息。 */
    private final String message;

    MultiServerErrorCode(final ErrorCategory category, final String message) {
        this.category = category;
        this.message = message;
    }

    /** @return 不可空类别；线程安全，无副作用。 */
    @Override
    public ErrorCategory category() { return category; }

    /** @return 不可空示例错误码；线程安全，无副作用。 */
    @Override
    public String code() { return "EXAMPLE-PLAYER-" + name(); }

    /** @return 不可空诊断信息；线程安全，无副作用。 */
    @Override
    public String message() { return message; }
}
