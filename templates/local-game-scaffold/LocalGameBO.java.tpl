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
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Demo 与网络入口共用的异步 BO；每次分发独立实例，服务状态仍属于 Actor。 @author zn */
public final class LocalGameBO implements GameLoginEventBO, GameEnterSceneEventBO, GameMoveEventBO {
    /** 线程安全的玩家服务端口。 */
    private final PlayerService players;
    /** 线程安全的场景服务端口。 */
    private final SceneService scenes;
    /** 共享日志与指标观察器。 */
    private final LocalGameObservation observation;
    /** 将本次请求的完成阶段交给传输/编排层，不保存跨请求状态。 */
    private final Consumer<CompletionStage<?>> result;

    /**
     * 创建请求级 BO；构造无副作用，不阻塞，result 在分发线程同步接收 stage。
     * @param players 玩家端口。 @param scenes 场景端口。 @param observation 观察器。 @param result 完成阶段接收器。
     * @throws NullPointerException 任一依赖为空。
     */
    public LocalGameBO(final PlayerService players, final SceneService scenes,
                       final LocalGameObservation observation, final Consumer<CompletionStage<?>> result) {
        this.players = Objects.requireNonNull(players, "players");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.observation = Objects.requireNonNull(observation, "observation");
        this.result = Objects.requireNonNull(result, "result");
    }

    /** @param request 登录请求；状态修改委托 player actor，不等待，异常交给结果阶段。 */
    @Override public void login(final GameLoginProtocolDTO request) {
        submit("login", request.traceId, () -> players.login(new PlayerLoginRequest(
                request.accountId, request.token, request.traceId)));
    }

    /** @param request 入场请求；状态修改委托 scene actor，不等待，异常交给结果阶段。 */
    @Override public void enterScene(final GameEnterSceneProtocolDTO request) {
        submit("enterScene", request.traceId, () -> scenes.enterScene(
                new SceneEnterRequest(request.uid, request.sceneId, request.traceId)));
    }

    /** @param request 移动请求；状态修改委托 scene actor，不等待，异常交给结果阶段。 */
    @Override public void move(final GameMoveProtocolDTO request) {
        submit("move", request.traceId, () -> scenes.moveWithResult(new SceneMoveRequest(
                request.uid, request.sceneId, new ScenePosition(request.x, request.y), request.traceId)));
    }

    private void submit(final String action, final String traceId, final Supplier<CompletionStage<?>> command) {
        observation.accepted(action, traceId);
        CompletionStage<?> stage;
        try {
            stage = Objects.requireNonNull(command.get(), "business stage");
        } catch (RuntimeException failure) {
            stage = CompletableFuture.failedFuture(failure);
        }
        result.accept(observation.observe(action, traceId, stage));
    }
}
