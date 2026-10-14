package __PACKAGE__;

import __PACKAGE__.generated.bo.GameEnterSceneEventBO;
import __PACKAGE__.generated.bo.GameLoginEventBO;
import __PACKAGE__.generated.bo.GameMoveEventBO;
import __PACKAGE__.generated.dto.GameEnterSceneProtocolDTO;
import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.GameMoveProtocolDTO;
import group.zn.zero.player.PlayerService;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.scene.SceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.ScenePosition;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Demo 与网络入口共用的异步 BO；每次分发独立实例，服务状态仍属于 Actor。 @author zn */
public final class LocalGameBO implements GameLoginEventBO.Async<group.zn.zero.player.PlayerLoginResult>,
        GameEnterSceneEventBO.Async<group.zn.zero.scene.SceneEntityState>,
        GameMoveEventBO.Async<group.zn.zero.scene.SceneMoveResult> {
    /** 线程安全的玩家服务端口。 */
    private final PlayerService players;
    /** 线程安全的场景服务端口。 */
    private final SceneService scenes;
    /** 共享日志与指标观察器。 */
    private final LocalGameObservation observation;

    /**
     * 创建无状态 BO；构造无副作用，直接返回类型化 stage。
     * @param players 玩家端口。 @param scenes 场景端口。 @param observation 观察器。
     * @throws NullPointerException 任一依赖为空。
     */
    public LocalGameBO(final PlayerService players, final SceneService scenes,
                       final LocalGameObservation observation) {
        this.players = Objects.requireNonNull(players, "players");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.observation = Objects.requireNonNull(observation, "observation");
    }

    /** @param request 登录请求；状态修改委托 player actor，不等待，异常交给结果阶段。 */
    @Override public CompletionStage<group.zn.zero.player.PlayerLoginResult> login(final GameLoginProtocolDTO request) {
        return submit("login", request.traceId, () -> players.login(new PlayerLoginRequest(
                request.accountId, request.token, request.traceId)));
    }

    /** @param request 入场请求；状态修改委托 scene actor，不等待，异常交给结果阶段。 */
    @Override public CompletionStage<group.zn.zero.scene.SceneEntityState> enterScene(final GameEnterSceneProtocolDTO request) {
        return submit("enterScene", request.traceId, () -> scenes.enterScene(
                new SceneEnterRequest(request.uid, request.sceneId, request.traceId)));
    }

    /** @param request 移动请求；状态修改委托 scene actor，不等待，异常交给结果阶段。 */
    @Override public CompletionStage<group.zn.zero.scene.SceneMoveResult> move(final GameMoveProtocolDTO request) {
        return submit("move", request.traceId, () -> scenes.moveWithResult(new SceneMoveRequest(
                request.uid, request.sceneId, new ScenePosition(request.x, request.y), request.traceId)));
    }

    private <T> CompletionStage<T> submit(final String action, final String traceId, final Supplier<CompletionStage<T>> command) {
        observation.accepted(action, traceId);
        CompletionStage<T> stage;
        try {
            stage = Objects.requireNonNull(command.get(), "business stage");
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        return observation.observe(action, traceId, stage);
    }
}
