package group.zn.zero.player;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.ErrorCode;

/** 玩家会话领域错误码。 @author zn */
public enum PlayerErrorCode implements ErrorCode {
    /** 账号已有在线会话；当前实现拒绝第二次登录。 */
    ACCOUNT_ALREADY_ONLINE(ErrorCategory.CLIENT_REQUEST, "ZERO-PLAYER-ACCOUNT-ALREADY-ONLINE",
            "account already has an online session");

    private final ErrorCategory category;
    private final String code;
    private final String message;

    PlayerErrorCode(final ErrorCategory category, final String code, final String message) {
        this.category = category;
        this.code = code;
        this.message = message;
    }

    @Override public ErrorCategory category() { return category; }
    @Override public String code() { return code; }
    @Override public String message() { return message; }
}
