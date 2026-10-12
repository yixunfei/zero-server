package group.zn.zero.examples.multiplayer.role;

import group.zn.zero.examples.multiplayer.AccountBinding;
import group.zn.zero.examples.multiplayer.PlayerLifecycleService;
import group.zn.zero.examples.multiplayer.PlayerSession;
import group.zn.zero.examples.multiplayer.rpc.LoginContract;
import group.zn.zero.examples.multiplayer.rpc.LoginRequest;
import group.zn.zero.examples.multiplayer.rpc.RegisterRequest;
import group.zn.zero.rpc.common.RpcResult;
import java.util.Objects;

/** 登录服职责的本地契约实现，须在玩家服务创建线程调用；尚未装配 RPC codec。 @author zn */
public final class LoginServer implements LoginContract {
    /** 当前组合根注入的业务依赖，不由本角色关闭。 */
    private final PlayerLifecycleService players;

    /**
     * 保存业务依赖，不创建资源、不改变状态。
     * @param players 非空业务依赖。
     * @throws NullPointerException 依赖为空。
     */
    public LoginServer(final PlayerLifecycleService players) {
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * 注册账号；重复请求返回 ACCOUNT_EXISTS，不覆盖绑定。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<AccountBinding> register(final RegisterRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> players.register(request.accountId()));
    }

    /**
     * 认证登录，重复登录保留会话与路由。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<PlayerSession> login(final LoginRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> players.login(request.accountId(), request.token(), request.traceId()));
    }
}
