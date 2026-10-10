package __PACKAGE__;

import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.LogAppender;
import group.zn.zero.player.PlayerService;
import group.zn.zero.scene.SceneService;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.runtime.api.GameRuntime;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** 统一协议分发与异步结果桥；每个请求独立收集结果，允许并发与相同 traceId。 @author zn */
public final class LocalGameFlow {
    /** 玩家端口。 */
    private final PlayerService players;
    /** 场景端口。 */
    private final SceneService scenes;
    /** 共享观察器。 */
    private final LocalGameObservation observation;

    /** @param players 玩家端口。 @param scenes 场景端口。 @param observation 观察器。无资源分配，依赖需线程安全。 */
    public LocalGameFlow(final PlayerService players, final SceneService scenes, final LocalGameObservation observation) {
        this.players = Objects.requireNonNull(players, "players");
        this.scenes = Objects.requireNonNull(scenes, "scenes");
        this.observation = Objects.requireNonNull(observation, "observation");
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
        AtomicReference<CompletionStage<?>> result = new AtomicReference<>();
        LocalGameBO business = new LocalGameBO(players, scenes, observation, result::set);
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        dispatcher.registerGameLoginEventBO(business);
        dispatcher.registerGameEnterSceneEventBO(business);
        dispatcher.registerGameMoveEventBO(business);
        try {
            if (!dispatcher.dispatch(id, payload) || result.get() == null) {
                return CompletableFuture.failedFuture(ZeroException.of(SystemErrorCode.INVALID_ARGUMENT));
            }
            return result.get().thenApply(value -> (Object) value);
        } catch (RuntimeException failure) {
            return CompletableFuture.failedFuture(LocalGameObservation.error(failure));
        }
    }

    /** @param config 框架配置。 @param log 安全日志端口。 @return 未启动 runtime，调用者负责启停；构造失败传播。 */
    public static GameRuntime createRuntime(final ZeroConfig config, final LogAppender log) {
        return RuntimeAssembly.create(config, log::append);
    }
}
