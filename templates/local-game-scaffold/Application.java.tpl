package __PACKAGE__;

import __PACKAGE__.generated.bo.GameEnterSceneEventBO;
import __PACKAGE__.generated.bo.GameLoginEventBO;
import __PACKAGE__.generated.bo.GameMoveEventBO;
import __PACKAGE__.generated.dto.GameEnterSceneProtocolDTO;
import __PACKAGE__.generated.dto.GameLoginProtocolDTO;
import __PACKAGE__.generated.dto.GameMoveProtocolDTO;
import __PACKAGE__.generated.dto.codec.GameEnterSceneProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.GameLoginProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.GameMoveProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.player.LocalPlayerService;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.starter.LocalRuntime;
import group.zn.zero.starter.LocalRuntimeCapabilities;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroServerApplication;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local game scaffold application.
 *
 * <p>This class wires generated protocol DTO, codec, BO and dispatcher to the zeroServer
 * local starter, player service, scene service, logs and metrics. It does not connect
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
    private static final LogSource LOG_SOURCE = new LogSource(APP_NAME, "local", APP_NAME);

    /**
     * Login metric name.
     */
    private static final String LOGIN_METRIC = "local_game_login_total";

    /**
     * Move metric name.
     */
    private static final String MOVE_METRIC = "local_game_move_total";

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
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        GameRuntime runtime = LocalRuntime.builder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                        ZeroRuntimeConfigKeys.ZERO_NAME, APP_NAME)),
                terminalLogSink,
                ZeroRuntimeExecutors.localPrototype(APP_NAME, 2))
                .replace(LocalRuntimeCapabilities.MONITOR_RUNTIME, monitorRuntime)
                .build();
        ZeroServerApplication application = new ZeroServerApplication(runtime);

        application.start();
        try (LocalPlayerService playerService = new LocalPlayerService(
                runtime.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER),
                request -> 1001L);
                LocalSceneService sceneService = new LocalSceneService(
                        runtime.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER))) {
            registerMetrics(monitorRuntime);
            FlowResults results = new FlowResults();
            GeneratedProtocolDispatcher dispatcher = registerHandlers(runtime, playerService, sceneService, results);

            dispatch(dispatcher, ProtocolIds.GAME_LOGIN_PROTOCOL, GameLoginProtocolDTOCodec.INSTANCE, loginRequest());
            dispatch(dispatcher, ProtocolIds.GAME_ENTER_SCENE_PROTOCOL,
                    GameEnterSceneProtocolDTOCodec.INSTANCE, enterSceneRequest());
            dispatch(dispatcher, ProtocolIds.GAME_MOVE_PROTOCOL, GameMoveProtocolDTOCodec.INSTANCE, moveRequest());

            return new DemoResult(
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    results.loginResult().uid(),
                    results.moveResult().currentState().position(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            application.stop();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(
            final GameRuntime runtime,
            final LocalPlayerService playerService,
            final LocalSceneService sceneService,
            final FlowResults results) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        LocalGameBO bo = new LocalGameBO(runtime, playerService, sceneService, results);
        dispatcher.registerGameLoginEventBO(bo);
        dispatcher.registerGameEnterSceneEventBO(bo);
        dispatcher.registerGameMoveEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                LOGIN_METRIC, "local game login count", "count", List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                MOVE_METRIC, "local game move count", "count", List.of("action")));
    }

    private static <T> void dispatch(
            final GeneratedProtocolDispatcher dispatcher,
            final int protocolId,
            final ZeroPayloadCodec<T> codec,
            final T request) {
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(codec, "codec");
        Objects.requireNonNull(request, "request");
        try (ZeroWriter writer = new ZeroWriter()) {
            codec.write(writer, request);
            if (!dispatcher.dispatch(protocolId, writer.toByteArray())) {
                throw new IllegalStateException("protocol handler not found: " + protocolId);
            }
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
     * Local generated-protocol business implementation.
     *
     * @author zn
     */
    private static final class LocalGameBO implements GameLoginEventBO, GameEnterSceneEventBO, GameMoveEventBO {

        /**
         * Runtime components.
         */
        private final GameRuntime runtime;

        /**
         * Player service.
         */
        private final LocalPlayerService playerService;

        /**
         * Scene service.
         */
        private final LocalSceneService sceneService;

        /**
         * Flow results.
         */
        private final FlowResults results;

        private LocalGameBO(
                final GameRuntime runtime,
                final LocalPlayerService playerService,
                final LocalSceneService sceneService,
                final FlowResults results) {
            this.runtime = Objects.requireNonNull(runtime, "runtime");
            this.playerService = Objects.requireNonNull(playerService, "playerService");
            this.sceneService = Objects.requireNonNull(sceneService, "sceneService");
            this.results = Objects.requireNonNull(results, "results");
        }

        /**
         * Handles login protocol event.
         *
         * @param request login request; never null.
         */
        @Override
        public void login(final GameLoginProtocolDTO request) {
            PlayerLoginResult result = playerService.login(new PlayerLoginRequest(
                    request.accountId,
                    request.token,
                    request.traceId)).toCompletableFuture().join();
            results.loginResult(result);
            record(request.traceId, "local login completed", LOGIN_METRIC, "login");
        }

        /**
         * Handles enter-scene protocol event.
         *
         * @param request enter-scene request; never null.
         */
        @Override
        public void enterScene(final GameEnterSceneProtocolDTO request) {
            sceneService.enterScene(new SceneEnterRequest(
                    request.uid,
                    request.sceneId,
                    request.traceId)).toCompletableFuture().join();
            appendLog(request.traceId, "local scene entered", "enter");
        }

        /**
         * Handles move protocol event.
         *
         * @param request move request; never null.
         */
        @Override
        public void move(final GameMoveProtocolDTO request) {
            SceneMoveResult result = sceneService.moveWithResult(new SceneMoveRequest(
                    request.uid,
                    request.sceneId,
                    new ScenePosition(request.x, request.y),
                    request.traceId)).toCompletableFuture().join();
            results.moveResult(result);
            record(request.traceId, "local scene moved", MOVE_METRIC, "move");
        }

        private void record(
                final String traceId,
                final String message,
                final String metricName,
                final String action) {
            appendLog(traceId, message, action);
            runtime.require(LocalRuntimeCapabilities.MONITOR_RUNTIME).registry().record(new MetricSample(
                    metricName,
                    1D,
                    Map.of("action", action),
                    Instant.now()));
        }

        private void appendLog(final String traceId, final String message, final String action) {
            runtime.require(LocalRuntimeCapabilities.LOG_APPENDER).append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(action, LogResult.SUCCESS, null),
                    traceId,
                    message,
                    Map.of("action", action)));
        }
    }

    /**
     * Flow results.
     *
     * @author zn
     */
    private static final class FlowResults {

        /**
         * Login result.
         */
        private final AtomicReference<PlayerLoginResult> loginResult = new AtomicReference<>();

        /**
         * Move result.
         */
        private final AtomicReference<SceneMoveResult> moveResult = new AtomicReference<>();

        private void loginResult(final PlayerLoginResult value) {
            loginResult.set(Objects.requireNonNull(value, "value"));
        }

        private PlayerLoginResult loginResult() {
            return loginResult.get();
        }

        private void moveResult(final SceneMoveResult value) {
            moveResult.set(Objects.requireNonNull(value, "value"));
        }

        private SceneMoveResult moveResult() {
            return moveResult.get();
        }
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
