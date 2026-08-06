package group.zn.zero.player;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 玩家基础服务。
 *
 * <p>该接口承载阶段 3 首版可直接接入的登录、玩家加载和玩家查询能力。
 * 实现必须保证玩家在线状态在 player lane 内修改，会话绑定在 session lane 内修改。
 *
 * @author zn
 */
public interface PlayerService {

    /**
     * 登录并绑定账号到玩家 ID。
     *
     * <p>数据变更：写入账号到玩家 ID 的会话绑定。线程安全性由实现保证。
     *
     * @param request 登录请求；不可为空。
     * @return 登录结果；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<PlayerLoginResult> login(PlayerLoginRequest request);

    /**
     * 加载玩家在线数据。
     *
     * <p>数据变更：在 player lane 内创建或替换玩家在线档案。线程安全性由实现保证。
     *
     * @param request 玩家加载请求；不可为空。
     * @return 玩家在线档案；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<PlayerProfile> loadPlayer(PlayerLoadRequest request);

    /**
     * 查询玩家在线档案。
     *
     * <p>数据变更：无。返回 Optional 可能为空；Optional 本身不可变、无序且线程安全。
     *
     * @param uid 玩家 ID。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 玩家档案 Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<Optional<PlayerProfile>> queryPlayer(long uid, String traceId);

    /**
     * 查询账号绑定的玩家 ID。
     *
     * <p>数据变更：无。返回 Optional 可能为空；Optional 本身不可变、无序且线程安全。
     *
     * @param accountId 账号 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 玩家 ID Optional；不可为空；异步完成；失败时必须绑定 ErrorCode。
     */
    CompletionStage<Optional<Long>> querySession(String accountId, String traceId);
}
