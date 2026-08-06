package group.zn.zero.player;

import java.util.Objects;

/**
 * 玩家登录请求。
 *
 * <p>该请求只承载登录入口所需的最小字段，不在框架内解释 token 语义。
 * record 不可变且线程安全；不会修改业务状态。
 *
 * @param accountId 账号 ID。
 * @param token 登录 token。
 * @param traceId 链路追踪 ID。
 * @author zn
 */
public record PlayerLoginRequest(String accountId, String token, String traceId) {

    /**
     * 创建玩家登录请求。
     *
     * @throws NullPointerException 当任一字段为空时抛出。
     * @throws IllegalArgumentException 当任一字段为空白时抛出。
     */
    public PlayerLoginRequest {
        accountId = requireText(accountId, "accountId");
        token = requireText(token, "token");
        traceId = requireText(traceId, "traceId");
    }

    private static String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }
}
