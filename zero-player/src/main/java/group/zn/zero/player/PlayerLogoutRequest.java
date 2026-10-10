package group.zn.zero.player;

import java.util.Objects;

/** 以登录返回的会话身份安全解除账号和玩家的在线绑定。 */
public record PlayerLogoutRequest(String accountId, long uid, String sessionId, String traceId) {
    /** 校验登出所需的精确会话身份。 */
    public PlayerLogoutRequest {
        accountId = requireText(accountId, "accountId");
        sessionId = requireText(sessionId, "sessionId");
        traceId = requireText(traceId, "traceId");
    }

    private static String requireText(final String value, final String name) {
        String text = Objects.requireNonNull(value, name);
        if (text.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return text;
    }
}
