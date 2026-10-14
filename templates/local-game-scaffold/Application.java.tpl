package __PACKAGE__;

import __PACKAGE__.generated.dto.codec.GameEnterSceneProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.GameLoginProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.GameMoveProtocolDTOCodec;
import __PACKAGE__.generated.dto.GameEnterSceneProtocolDTO;
import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.GameMoveProtocolDTO;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import java.time.Instant;
import java.util.Map;

/**
 * Local game scaffold application.
 *
 * <p>This class wires generated protocol DTO, codec, BO and dispatcher to the zeroServer
 * selected runtime components, player service, scene service, logs and metrics. It does not connect
 * external middleware and is not a production deployment template.</p>
 *
 * @author zn
 */
public final class __APP_CLASS__ {

    /**
     * Application name.
     */
    private static final String APP_NAME = "__PROJECT_NAME__";

    /**
     * Stable local log source.
     */
    private static final LogSource LOG_SOURCE = new LogSource(APP_NAME, "__RUNTIME_PROFILE__", APP_NAME);

    private __APP_CLASS__() {
    }

    /**
     * Starts the local game scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol demo flow.
     *
     * @return demo result; never null; thread-safe.
     */
    public static DemoResult runDemo() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        GameRuntime runtime = RuntimeAssembly.create(
                ZeroConfigLoader.loadStandard(Map.ofEntries(
                        __CONFIG_DEFAULTS__,
                        Map.entry(ZeroRuntimeConfigKeys.ZERO_NAME, APP_NAME))),
                terminalLogSink);
        MonitorRuntime monitorRuntime = runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME);

        runtime.start();
        runtime.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                Instant.now(), LogLevel.INFO, LogType.RUNTIME, LOG_SOURCE,
                new LogOperation("runtime-start", LogResult.SUCCESS, null),
                "bootstrap", "zeroServer started", Map.of("name", APP_NAME)));
        try (LocalGameFixture fixture = new LocalGameFixture(runtime)) {
            LocalGameObservation observation = new LocalGameObservation(runtime.require(LogRuntime.LOG_APPENDER),
                    monitorRuntime, LOG_SOURCE);
            LocalGameFlow flow = new LocalGameFlow(fixture.playerService(), fixture.sceneService(), observation);
            // 只有命令行编排层等待结果；BO、Actor、网络处理器始终异步。
            PlayerLoginResult login = (PlayerLoginResult) flow.dispatch(ProtocolIds.GAME_LOGIN_PROTOCOL,
                    GameLoginProtocolDTOCodec.INSTANCE, loginRequest()).toCompletableFuture().join();
            flow.dispatch(ProtocolIds.GAME_ENTER_SCENE_PROTOCOL,
                    GameEnterSceneProtocolDTOCodec.INSTANCE, enterSceneRequest()).toCompletableFuture().join();
            SceneMoveResult move = (SceneMoveResult) flow.dispatch(ProtocolIds.GAME_MOVE_PROTOCOL,
                    GameMoveProtocolDTOCodec.INSTANCE, moveRequest()).toCompletableFuture().join();
            return new DemoResult(
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    login.uid(), move.currentState().position(), terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(), ProtocolIds.MAX_ID);
        } finally {
            runtime.close();
        }
    }

    private static GameLoginProtocolDTO loginRequest() {
        GameLoginProtocolDTO request = new GameLoginProtocolDTO();
        request.accountId = "guest-1001";
        request.token = "local-token";
        request.traceId = "trace-local-login";
        return request;
    }

    private static GameEnterSceneProtocolDTO enterSceneRequest() {
        GameEnterSceneProtocolDTO request = new GameEnterSceneProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "scene-1";
        request.traceId = "trace-local-enter";
        return request;
    }

    private static GameMoveProtocolDTO moveRequest() {
        GameMoveProtocolDTO request = new GameMoveProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "scene-1";
        request.x = 3;
        request.y = 5;
        request.traceId = "trace-local-move";
        return request;
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param uid player ID.
     * @param position position after move.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            long uid,
            ScenePosition position,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "local-game=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|uid=" + uid
                    + "|position=" + position.x() + "," + position.y()
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
