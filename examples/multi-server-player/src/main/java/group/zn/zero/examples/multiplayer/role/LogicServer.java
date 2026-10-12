package group.zn.zero.examples.multiplayer.role;

import group.zn.zero.examples.multiplayer.PlayerCharacter;
import group.zn.zero.examples.multiplayer.PlayerLifecycleService;
import group.zn.zero.examples.multiplayer.rpc.CreateCharacterRequest;
import group.zn.zero.examples.multiplayer.rpc.DisconnectRequest;
import group.zn.zero.examples.multiplayer.rpc.LogicContract;
import group.zn.zero.rpc.common.RpcResult;
import java.util.Objects;

/** 逻辑服职责的本地契约实现，须在玩家服务创建线程调用；尚未装配 RPC codec。 @author zn */
public final class LogicServer implements LogicContract {
    /** 当前组合根注入的业务依赖，不由本角色关闭。 */
    private final PlayerLifecycleService players;

    /**
     * 保存业务依赖，不创建资源、不改变状态。
     * @param players 非空业务依赖。
     * @throws NullPointerException 依赖为空。
     */
    public LogicServer(final PlayerLifecycleService players) {
        this.players = Objects.requireNonNull(players, "players");
    }

    /**
     * 校验有效会话并创建角色，重复创建返回 CHARACTER_EXISTS。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<PlayerCharacter> create(final CreateCharacterRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> players.createCharacter(request.session(), request.name()));
    }

    /**
     * 精确断线；旧会话返回 false，不影响新会话。
     * @param request 非空、已校验请求；线程约束与本类一致。
     * @return 不可空结果；业务异常保留 ErrorCode，状态变化见对应服务。
     * @throws NullPointerException request 为空；其他未分类故障继续交给上层处理。
     */
    @Override
    public RpcResult<Boolean> disconnect(final DisconnectRequest request) {
        Objects.requireNonNull(request, "request");
        return RoleResults.call(() -> players.disconnect(request.session(), request.traceId()));
    }
}
