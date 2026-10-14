package __PACKAGE__;

import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.core.config.ZeroConfig;
import __PACKAGE__.generated.dto.GameLoginResultProtocolDTO;
import __PACKAGE__.generated.dto.GameSceneResultProtocolDTO;
import __PACKAGE__.generated.dto.codec.GameLoginResultProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.GameSceneResultProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.log.LogAppender;
import group.zn.zero.player.PlayerService;
import group.zn.zero.scene.SceneService;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.concurrent.CompletionStage;

/** 统一协议分发与类型化异步响应；每个请求独立返回结果，允许并发与相同 traceId。 @author zn */
public final class LocalGameFlow {
    /** 启动时注册并安全发布的生成分发器。 */
    private final GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
    /** 帧端点启动后不修改；并发安全性由业务端口提供。 */
    private final java.util.Map<Integer, java.util.function.Function<ProtocolFrame, CompletionStage<ProtocolFrame>>> endpoints;

    /** @param players 玩家端口。 @param scenes 场景端口。 @param observation 观察器。无资源分配，依赖需线程安全。 */
    public LocalGameFlow(final PlayerService players, final SceneService scenes, final LocalGameObservation observation) {
        var business = new LocalGameBO(players, scenes, observation);
        dispatcher.registerGameLoginEventBOAsync(business);
        dispatcher.registerGameEnterSceneEventBOAsync(business);
        dispatcher.registerGameMoveEventBOAsync(business);
        var login = GeneratedProtocolDispatcher.endpointGameLoginEventBOlogin(
                request -> business.login(request).thenApply(LocalGameFlow::loginResult),
                ProtocolIds.GAME_LOGIN_RESULT_PROTOCOL, GameLoginResultProtocolDTOCodec.INSTANCE);
        var enter = GeneratedProtocolDispatcher.endpointGameEnterSceneEventBOenterScene(
                request -> business.enterScene(request).thenApply(LocalGameFlow::sceneResult),
                ProtocolIds.GAME_SCENE_RESULT_PROTOCOL, GameSceneResultProtocolDTOCodec.INSTANCE);
        var move = GeneratedProtocolDispatcher.endpointGameMoveEventBOmove(
                request -> business.move(request).thenApply(result -> sceneResult(result.currentState())),
                ProtocolIds.GAME_SCENE_RESULT_PROTOCOL, GameSceneResultProtocolDTOCodec.INSTANCE);
        endpoints = java.util.Map.of(ProtocolIds.GAME_LOGIN_PROTOCOL, login::handle,
                ProtocolIds.GAME_ENTER_SCENE_PROTOCOL, enter::handle, ProtocolIds.GAME_MOVE_PROTOCOL, move::handle);
    }

    /** @param id 协议 ID。 @param codec 编码器。 @param request 请求。 @param <T> 请求类型。
     * @return 业务实际完成结果，不代表仅已接收；无阻塞。编码失败向调用者传播。 */
    public <T> CompletionStage<Object> dispatch(final int id, final ZeroPayloadCodec<T> codec, final T request) {
        try (ZeroWriter writer = new ZeroWriter()) {
            codec.write(writer, request);
            return dispatch(id, writer.toByteArray());
        }
    }

    /** @param id 协议 ID。 @param payload 请求字节。 @return 实际完成阶段；解析/同步/异步失败统一传播，不阻塞。 */
    public CompletionStage<Object> dispatch(final int id, final byte[] payload) {
        return dispatcher.dispatchAsync(id, payload);
    }

    /** 帧入口返回生成 DTO 的真实业务结果；不阻塞，错误沿用标准错误帧。
     * @param frame 请求帧。 @return 完成后的响应帧；线程安全。 */
    public CompletionStage<ProtocolFrame> handle(final ProtocolFrame frame) {
        var endpoint = endpoints.get(frame.protocolId());
        return endpoint == null ? java.util.concurrent.CompletableFuture.completedFuture(
                group.zn.zero.protocol.ProtocolErrorFrames.response(frame,
                        group.zn.zero.protocol.error.ProtocolErrorCode.PROTOCOL_NOT_FOUND.code())) : endpoint.apply(frame);
    }

    private static GameLoginResultProtocolDTO loginResult(final group.zn.zero.player.PlayerLoginResult value) {
        var result = new GameLoginResultProtocolDTO();
        result.uid = value.uid(); result.accountId = value.accountId();
        result.sessionId = value.sessionId(); result.traceId = value.traceId();
        return result;
    }

    private static GameSceneResultProtocolDTO sceneResult(final group.zn.zero.scene.SceneEntityState value) {
        var result = new GameSceneResultProtocolDTO();
        result.uid = value.uid(); result.sceneId = value.sceneId();
        result.x = value.position().x(); result.y = value.position().y();
        return result;
    }

    /** @param config 框架配置。 @param log 安全日志端口。 @return 未启动 runtime，调用者负责启停；构造失败传播。 */
    public static GameRuntime createRuntime(final ZeroConfig config, final LogAppender log) {
        return RuntimeAssembly.create(config, log::append);
    }
}
