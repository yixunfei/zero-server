package group.zn.zero.game;

/**
 * 游戏业务执行域。
 *
 * <p>该枚举用于阶段 3 原型描述业务代码应进入的执行边界。它不直接创建线程池，
 * 具体执行器由 starter 或业务项目统一装配。
 *
 * @author zn
 */
public enum GameExecutionDomain {

    /**
     * 会话与账号执行域，用于登录态、账号到玩家 ID 绑定和入口鉴权之后的轻量状态。
     */
    SESSION,

    /**
     * 玩家执行域，用于玩家在线数据、玩家加载结果和玩家视角只读查询。
     */
    PLAYER,

    /**
     * 场景执行域，用于场景实体、坐标、进入场景和移动。
     */
    SCENE,

    /**
     * 远程 IO 执行域，用于数据库、RPC、消息队列和其他不可控远程调用。
     */
    REMOTE_IO,

    /**
     * 后台执行域，用于低频 GM、配置刷新、统计和可取消后台任务。
     */
    BACKGROUND
}
