package group.zn.zero.player;

import java.util.Objects;

/**
 * 玩家登录结果。
 *
 * <p>该结果表示账号已绑定到玩家 ID。record 不可变且线程安全；不会修改业务状态。
 *
 * @param accountId 账号 ID。
 * @param uid 玩家 ID。
 * @param traceId 链路追踪 ID。
 * @author zn
 */
public record PlayerLoginResult(String accountId, long uid, String traceId) {

    /**
     * 创建玩家登录结果。
     *
     * @throws NullPointerException 当 accountId 或 traceId 为空时抛出。
     * @throws IllegalArgumentException 当 accountId 或 traceId 为空白时抛出。
     */
    public PlayerLoginResult {
        accountId = requireText(accountId, "accountId");
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
