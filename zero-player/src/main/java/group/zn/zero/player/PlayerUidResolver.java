package group.zn.zero.player;

/**
 * 玩家登录 UID 解析器。
 *
 * <p>该接口用于把账号登录请求解析为玩家 ID。实现应避免在 Actor handler 内执行远程 IO；
 * 如果需要访问远程鉴权服务，应先在远程 IO 执行域完成，再投递到 player/session lane。
 *
 * @author zn
 */
@FunctionalInterface
public interface PlayerUidResolver {

    /**
     * 解析玩家 ID。
     *
     * @param request 登录请求；不可为空。
     * @return 玩家 ID；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 解析失败时抛出，必须绑定 ErrorCode。
     */
    long resolve(PlayerLoginRequest request);
}
