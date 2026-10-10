package group.zn.zero.player;

import java.util.Objects;
import java.util.concurrent.CompletionStage;

/**
 * 将传输层连接关闭事件转换为精确的玩家会话解绑请求。
 *
 * <p>协调器不依赖任何网络实现。装配层必须保存登录结果中的 {@code sessionId}，并在对应连接关闭时构造
 * {@link PlayerLogoutRequest}。延迟到达的旧连接事件会因会话身份不匹配而返回 {@code false}，不能移除新登录。</p>
 *
 * @author zn
 */
public final class PlayerConnectionCloseCoordinator {

    /** 玩家会话服务。 */
    private final PlayerService playerService;

    /**
     * 创建连接关闭协调器。
     *
     * @param playerService 玩家会话服务；不可为空。
     */
    public PlayerConnectionCloseCoordinator(final PlayerService playerService) {
        this.playerService = Objects.requireNonNull(playerService, "playerService");
    }

    /**
     * 处理一个已认证连接的关闭事件。
     *
     * <p>线程安全：直接委托 {@link PlayerService#logout(PlayerLogoutRequest)}，不创建线程、不修改传输状态。
     * 调用方应在同一关闭回调中以连接对象身份关闭自身的逻辑会话目录。</p>
     *
     * @param request 登录时绑定的精确会话身份；不可为空。
     * @return 解绑结果阶段；{@code true} 表示当前绑定被移除，{@code false} 表示已被替换或已关闭；不可为空。
     */
    public CompletionStage<Boolean> onConnectionClosed(final PlayerLogoutRequest request) {
        return playerService.logout(Objects.requireNonNull(request, "request"));
    }
}
