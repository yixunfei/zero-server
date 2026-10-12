package group.zn.zero.examples.multiplayer;

import java.util.Objects;

/**
 * 不可变、线程安全的登录身份快照；业务必须与当前服务端会话逐字段核对。
 * @param accountId 非空账号。
 * @param uid 正数玩家 ID。
 * @param sessionId 非空框架会话 ID。
 * @param logicServerId 非空已分配逻辑服 ID。
 * @author zn
 */
public record PlayerSession(String accountId, long uid, String sessionId, String logicServerId) {
    /** 校验字段但不认证身份；非法值抛 IllegalArgumentException，空文本抛 NullPointerException。 */
    public PlayerSession {
        require(accountId, "accountId");
        require(sessionId, "sessionId");
        require(logicServerId, "logicServerId");
        if (uid <= 0) {
            throw new IllegalArgumentException("uid must be positive");
        }
    }

    private static void require(final String value, final String name) {
        if (Objects.requireNonNull(value, name).isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
