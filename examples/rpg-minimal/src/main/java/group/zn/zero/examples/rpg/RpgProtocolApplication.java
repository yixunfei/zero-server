package group.zn.zero.examples.rpg;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.examples.rpg.generated.bo.RpgEnterSceneEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgGmQueryPlayerEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgGmQuerySceneEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgLeaveSceneEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgLoadPlayerEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgLoginEventBO;
import group.zn.zero.examples.rpg.generated.bo.RpgMoveEventBO;
import group.zn.zero.examples.rpg.generated.dto.RpgEnterSceneProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgGmQueryPlayerProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgGmQuerySceneProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgLeaveSceneProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgLoadPlayerProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgLoginProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.RpgMoveProtocolDTO;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgEnterSceneProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgGmQueryPlayerProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgGmQuerySceneProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgLeaveSceneProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgLoadPlayerProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgLoginProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.dto.codec.RpgMoveProtocolDTOCodec;
import group.zn.zero.examples.rpg.generated.protocol.ProtocolIds;
import group.zn.zero.examples.rpg.generated.protocol.dispatch.GeneratedProtocolDispatcher;
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
import group.zn.zero.player.PlayerLoadRequest;
import group.zn.zero.player.PlayerLoginRequest;
import group.zn.zero.player.PlayerLoginResult;
import group.zn.zero.player.PlayerProfile;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneLeaveRequest;
import group.zn.zero.scene.SceneLeaveResult;
import group.zn.zero.scene.SceneMoveRequest;
import group.zn.zero.scene.SceneMoveResult;
import group.zn.zero.scene.ScenePosition;
import group.zn.zero.starter.ZeroRuntimeAssemblyReport;
import group.zn.zero.starter.ZeroRuntimeComponents;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroRuntimeFactory;
import group.zn.zero.starter.ZeroServerApplication;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RPG 协议驱动本地示例。
 *
 * <p>本示例使用 Maven generate-sources 阶段由 `.si` 协议生成 DTO、codec、BO 和 dispatcher，
 * 再由手写业务 BO 调用 zero-player 与 zero-scene。本示例不连接外部中间件。</p>
 *
 * @author zn
 */
public final class RpgProtocolApplication {

    /**
     * 示例模块名。
     */
    private static final String MODULE = "rpg-protocol";

    /**
     * 示例固定日志来源。
     */
    private static final LogSource LOG_SOURCE = new LogSource(MODULE, "local", MODULE);

    /**
     * 登录指标名称。
     */
    private static final String LOGIN_METRIC = "zero_example_rpg_protocol_login_total";

    /**
     * 加载玩家指标名称。
     */
    private static final String LOAD_PLAYER_METRIC = "zero_example_rpg_protocol_player_load_total";

    /**
     * 场景移动指标名称。
     */
    private static final String MOVE_METRIC = "zero_example_rpg_protocol_scene_move_total";

    /**
     * GM 查询指标名称。
     */
    private static final String GM_QUERY_METRIC = "zero_example_rpg_protocol_gm_query_total";

    /**
     * 离开场景指标名称。
     */
    private static final String LEAVE_SCENE_METRIC = "zero_example_rpg_protocol_scene_leave_total";

    private RpgProtocolApplication() {
    }

    /**
     * 启动 RPG 协议驱动本地示例。
     *
     * @param args 命令行参数；当前未使用；可为空。
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * 执行协议驱动 RPG 本地流程。
     *
     * <p>本方法会编码生成 DTO，通过生成 dispatcher 分发到手写 BO，再由 BO 调用玩家和场景模块。
     * 返回结果不可变，不包含敏感信息。</p>
     *
     * @return 示例运行结果；不可为空；线程安全。
     */
    public static ProtocolDemoResult runDemo() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        ZeroRuntimeComponents components = ZeroRuntimeFactory.localBuilder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                        ZeroRuntimeConfigKeys.ZERO_NAME, MODULE)),
                terminalLogSink,
                ZeroRuntimeExecutors.localPrototype("zero-example-rpg-protocol", 2))
                .monitorRuntime(monitorRuntime)
                .build();
        ZeroServerApplication application = new ZeroServerApplication(components);

        application.start();
        try (LocalPlayerService playerService = new LocalPlayerService(
                components.actorScheduler(),
                request -> 1001L);
                LocalSceneService sceneService = new LocalSceneService(components.actorScheduler())) {
            registerMetrics(monitorRuntime);
            ProtocolFlowResults results = new ProtocolFlowResults();
            GeneratedProtocolDispatcher dispatcher = registerBusinessHandlers(
                    components,
                    playerService,
                    sceneService,
                    results);

            dispatch(dispatcher, ProtocolIds.RPG_LOGIN_PROTOCOL, RpgLoginProtocolDTOCodec.INSTANCE, loginRequest());
            dispatch(dispatcher, ProtocolIds.RPG_LOAD_PLAYER_PROTOCOL,
                    RpgLoadPlayerProtocolDTOCodec.INSTANCE, loadPlayerRequest());
            dispatch(dispatcher, ProtocolIds.RPG_ENTER_SCENE_PROTOCOL,
                    RpgEnterSceneProtocolDTOCodec.INSTANCE, enterSceneRequest());
            dispatch(dispatcher, ProtocolIds.RPG_MOVE_PROTOCOL, RpgMoveProtocolDTOCodec.INSTANCE, moveRequest());
            dispatch(dispatcher, ProtocolIds.RPG_GM_QUERY_PLAYER_PROTOCOL,
                    RpgGmQueryPlayerProtocolDTOCodec.INSTANCE, gmQueryPlayerRequest());
            dispatch(dispatcher, ProtocolIds.RPG_GM_QUERY_SCENE_PROTOCOL,
                    RpgGmQuerySceneProtocolDTOCodec.INSTANCE, gmQuerySceneBeforeRequest());
            dispatch(dispatcher, ProtocolIds.RPG_LEAVE_SCENE_PROTOCOL,
                    RpgLeaveSceneProtocolDTOCodec.INSTANCE, leaveSceneRequest());
            dispatch(dispatcher, ProtocolIds.RPG_GM_QUERY_SCENE_PROTOCOL,
                    RpgGmQuerySceneProtocolDTOCodec.INSTANCE, gmQuerySceneAfterRequest());

            ZeroRuntimeAssemblyReport report = components.assemblyReport();
            return new ProtocolDemoResult(
                    report.mode(),
                    report.name(),
                    results.loginResult().uid(),
                    results.loadedProfile().name(),
                    results.moveResult().currentState().position(),
                    results.gmSceneBeforeLeave(),
                    results.gmSceneAfterLeave(),
                    results.leaveResult().removed(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            application.stop();
        }
    }

    private static GeneratedProtocolDispatcher registerBusinessHandlers(
            final ZeroRuntimeComponents components,
            final LocalPlayerService playerService,
            final LocalSceneService sceneService,
            final ProtocolFlowResults results) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        ProtocolBusinessHandlers handlers = new ProtocolBusinessHandlers(
                components,
                playerService,
                sceneService,
                results);
        dispatcher.registerRpgLoginEventBO(handlers);
        dispatcher.registerRpgLoadPlayerEventBO(handlers);
        dispatcher.registerRpgEnterSceneEventBO(handlers);
        dispatcher.registerRpgMoveEventBO(handlers);
        dispatcher.registerRpgGmQueryPlayerEventBO(handlers);
        dispatcher.registerRpgGmQuerySceneEventBO(handlers);
        dispatcher.registerRpgLeaveSceneEventBO(handlers);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                LOGIN_METRIC, "RPG protocol login count", "count", List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                LOAD_PLAYER_METRIC,
                "RPG protocol player load count",
                "count",
                List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                MOVE_METRIC,
                "RPG protocol scene move count",
                "count",
                List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                GM_QUERY_METRIC,
                "RPG protocol GM query count",
                "count",
                List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                LEAVE_SCENE_METRIC,
                "RPG protocol scene leave count",
                "count",
                List.of("action")));
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

    private static RpgLoginProtocolDTO loginRequest() {
        RpgLoginProtocolDTO request = new RpgLoginProtocolDTO();
        request.accountId = "guest-1001";
        request.token = "local-token";
        request.traceId = "trace-rpg-protocol-login";
        return request;
    }

    private static RpgLoadPlayerProtocolDTO loadPlayerRequest() {
        RpgLoadPlayerProtocolDTO request = new RpgLoadPlayerProtocolDTO();
        request.uid = 1001L;
        request.traceId = "trace-rpg-protocol-load";
        return request;
    }

    private static RpgEnterSceneProtocolDTO enterSceneRequest() {
        RpgEnterSceneProtocolDTO request = new RpgEnterSceneProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "scene-1";
        request.traceId = "trace-rpg-protocol-enter";
        return request;
    }

    private static RpgMoveProtocolDTO moveRequest() {
        RpgMoveProtocolDTO request = new RpgMoveProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "scene-1";
        request.x = 7;
        request.y = 11;
        request.traceId = "trace-rpg-protocol-move";
        return request;
    }

    private static RpgGmQueryPlayerProtocolDTO gmQueryPlayerRequest() {
        RpgGmQueryPlayerProtocolDTO request = new RpgGmQueryPlayerProtocolDTO();
        request.uid = 1001L;
        request.operatorId = "gm-local";
        request.traceId = "trace-rpg-protocol-gm-player";
        return request;
    }

    private static RpgGmQuerySceneProtocolDTO gmQuerySceneBeforeRequest() {
        RpgGmQuerySceneProtocolDTO request = new RpgGmQuerySceneProtocolDTO();
        request.sceneId = "scene-1";
        request.traceId = "trace-rpg-protocol-gm-scene-before";
        return request;
    }

    private static RpgLeaveSceneProtocolDTO leaveSceneRequest() {
        RpgLeaveSceneProtocolDTO request = new RpgLeaveSceneProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "scene-1";
        request.traceId = "trace-rpg-protocol-leave";
        return request;
    }

    private static RpgGmQuerySceneProtocolDTO gmQuerySceneAfterRequest() {
        RpgGmQuerySceneProtocolDTO request = new RpgGmQuerySceneProtocolDTO();
        request.sceneId = "scene-1";
        request.traceId = "trace-rpg-protocol-gm-scene-after";
        return request;
    }

    private static String sceneSummary(
            final String sceneId,
            final List<group.zn.zero.scene.SceneEntityState> entities) {
        if (entities.isEmpty()) {
            return "scene=" + sceneId + "|entities=0";
        }
        group.zn.zero.scene.SceneEntityState first = entities.getFirst();
        return "scene=" + sceneId
                + "|entities=" + entities.size()
                + "|first=" + first.uid()
                + "@" + first.position().x()
                + "," + first.position().y();
    }

    /**
     * 协议驱动示例业务处理器。
     *
     * @author zn
     */
    private static final class ProtocolBusinessHandlers implements
            RpgLoginEventBO,
            RpgLoadPlayerEventBO,
            RpgEnterSceneEventBO,
            RpgMoveEventBO,
            RpgGmQueryPlayerEventBO,
            RpgGmQuerySceneEventBO,
            RpgLeaveSceneEventBO {

        /**
         * 运行时组件。
         */
        private final ZeroRuntimeComponents components;

        /**
         * 玩家服务。
         */
        private final LocalPlayerService playerService;

        /**
         * 场景服务。
         */
        private final LocalSceneService sceneService;

        /**
         * 流程结果。
         */
        private final ProtocolFlowResults results;

        private ProtocolBusinessHandlers(
                final ZeroRuntimeComponents components,
                final LocalPlayerService playerService,
                final LocalSceneService sceneService,
                final ProtocolFlowResults results) {
            this.components = Objects.requireNonNull(components, "components");
            this.playerService = Objects.requireNonNull(playerService, "playerService");
            this.sceneService = Objects.requireNonNull(sceneService, "sceneService");
            this.results = Objects.requireNonNull(results, "results");
        }

        /**
         * 处理协议登录事件。
         *
         * @param request 登录请求 DTO；不可为空。
         */
        @Override
        public void login(final RpgLoginProtocolDTO request) {
            PlayerLoginResult result = playerService.login(new PlayerLoginRequest(
                    request.accountId,
                    request.token,
                    request.traceId)).toCompletableFuture().join();
            results.loginResult(result);
            record(request.traceId, "protocol login completed", LOGIN_METRIC, "login");
        }

        /**
         * 处理协议加载玩家事件。
         *
         * @param request 加载玩家请求 DTO；不可为空。
         */
        @Override
        public void loadPlayer(final RpgLoadPlayerProtocolDTO request) {
            PlayerProfile profile = playerService.loadPlayer(new PlayerLoadRequest(
                    request.uid,
                    request.traceId)).toCompletableFuture().join();
            results.loadedProfile(profile);
            record(request.traceId, "protocol player loaded", LOAD_PLAYER_METRIC, "load");
        }

        /**
         * 处理协议进入场景事件。
         *
         * @param request 进入场景请求 DTO；不可为空。
         */
        @Override
        public void enterScene(final RpgEnterSceneProtocolDTO request) {
            sceneService.enterScene(new SceneEnterRequest(
                    request.uid,
                    request.sceneId,
                    request.traceId)).toCompletableFuture().join();
            appendLog(request.traceId, "protocol scene entered", "enter");
        }

        /**
         * 处理协议场景移动事件。
         *
         * @param request 场景移动请求 DTO；不可为空。
         */
        @Override
        public void move(final RpgMoveProtocolDTO request) {
            SceneMoveResult result = sceneService.moveWithResult(new SceneMoveRequest(
                    request.uid,
                    request.sceneId,
                    new ScenePosition(request.x, request.y),
                    request.traceId)).toCompletableFuture().join();
            results.moveResult(result);
            record(request.traceId, "protocol scene moved", MOVE_METRIC, "move");
        }

        /**
         * 处理协议 GM 查询玩家事件。
         *
         * @param request GM 查询玩家请求 DTO；不可为空。
         */
        @Override
        public void gmQueryPlayer(final RpgGmQueryPlayerProtocolDTO request) {
            PlayerProfile profile = playerService.queryPlayer(
                    request.uid,
                    request.traceId).toCompletableFuture().join().orElseThrow();
            results.gmPlayerResult("uid=" + profile.uid()
                    + "|name=" + profile.name()
                    + "|online=" + profile.online());
            record(request.traceId, "protocol gm player queried", GM_QUERY_METRIC, "gm-player");
        }

        /**
         * 处理协议 GM 查询场景事件。
         *
         * @param request GM 查询场景请求 DTO；不可为空。
         */
        @Override
        public void gmQueryScene(final RpgGmQuerySceneProtocolDTO request) {
            List<group.zn.zero.scene.SceneEntityState> entities = sceneService.listEntities(
                    request.sceneId,
                    request.traceId).toCompletableFuture().join();
            String value = sceneSummary(request.sceneId, entities);
            if (entities.isEmpty()) {
                results.gmSceneAfterLeave(value);
            } else {
                results.gmSceneBeforeLeave(value);
            }
            record(request.traceId, "protocol gm scene queried", GM_QUERY_METRIC, "gm-scene");
        }

        /**
         * 处理协议离开场景事件。
         *
         * @param request 离开场景请求 DTO；不可为空。
         */
        @Override
        public void leaveScene(final RpgLeaveSceneProtocolDTO request) {
            SceneLeaveResult result = sceneService.leaveScene(new SceneLeaveRequest(
                    request.uid,
                    request.sceneId,
                    request.traceId)).toCompletableFuture().join();
            results.leaveResult(result);
            record(request.traceId, "protocol scene left", LEAVE_SCENE_METRIC, "leave");
        }

        private void record(
                final String traceId,
                final String message,
                final String metricName,
                final String action) {
            appendLog(traceId, message, action);
            components.monitorRuntime().registry().record(new MetricSample(
                    metricName,
                    1D,
                    Map.of("action", action),
                    Instant.now()));
        }

        private void appendLog(final String traceId, final String message, final String action) {
            components.logAppender().append(ZeroLogRecord.create(
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
     * 协议驱动流程结果收集器。
     *
     * @author zn
     */
    private static final class ProtocolFlowResults {

        /**
         * 登录结果。
         */
        private final AtomicReference<PlayerLoginResult> loginResult = new AtomicReference<>();

        /**
         * 玩家档案。
         */
        private final AtomicReference<PlayerProfile> loadedProfile = new AtomicReference<>();

        /**
         * 移动结果。
         */
        private final AtomicReference<SceneMoveResult> moveResult = new AtomicReference<>();

        /**
         * 离开场景结果。
         */
        private final AtomicReference<SceneLeaveResult> leaveResult = new AtomicReference<>();

        /**
         * GM 玩家查询结果。
         */
        private final AtomicReference<String> gmPlayerResult = new AtomicReference<>("");

        /**
         * 离开前 GM 场景查询结果。
         */
        private final AtomicReference<String> gmSceneBeforeLeave = new AtomicReference<>("");

        /**
         * 离开后 GM 场景查询结果。
         */
        private final AtomicReference<String> gmSceneAfterLeave = new AtomicReference<>("");

        private void loginResult(final PlayerLoginResult value) {
            loginResult.set(Objects.requireNonNull(value, "value"));
        }

        private PlayerLoginResult loginResult() {
            return loginResult.get();
        }

        private void loadedProfile(final PlayerProfile value) {
            loadedProfile.set(Objects.requireNonNull(value, "value"));
        }

        private PlayerProfile loadedProfile() {
            return loadedProfile.get();
        }

        private void moveResult(final SceneMoveResult value) {
            moveResult.set(Objects.requireNonNull(value, "value"));
        }

        private SceneMoveResult moveResult() {
            return moveResult.get();
        }

        private void leaveResult(final SceneLeaveResult value) {
            leaveResult.set(Objects.requireNonNull(value, "value"));
        }

        private SceneLeaveResult leaveResult() {
            return leaveResult.get();
        }

        private void gmPlayerResult(final String value) {
            gmPlayerResult.set(Objects.requireNonNull(value, "value"));
        }

        private String gmSceneBeforeLeave() {
            return gmSceneBeforeLeave.get();
        }

        private void gmSceneBeforeLeave(final String value) {
            gmSceneBeforeLeave.set(Objects.requireNonNull(value, "value"));
        }

        private String gmSceneAfterLeave() {
            return gmSceneAfterLeave.get();
        }

        private void gmSceneAfterLeave(final String value) {
            gmSceneAfterLeave.set(Objects.requireNonNull(value, "value"));
        }
    }

    /**
     * 协议驱动示例运行结果。
     *
     * @param mode runtime 模式。
     * @param name runtime 名称。
     * @param uid 玩家 ID。
     * @param playerName 玩家名。
     * @param position 移动后的场景坐标。
     * @param sceneBeforeLeave 离开场景前场景查询摘要。
     * @param sceneAfterLeave 离开场景后场景查询摘要。
     * @param leaveRemoved 离开场景时是否移除了实体。
     * @param logCount 日志数量。
     * @param metricCount 指标样本数量。
     * @param maxProtocolId 最大协议号。
     * @author zn
     */
    public record ProtocolDemoResult(
            String mode,
            String name,
            long uid,
            String playerName,
            ScenePosition position,
            String sceneBeforeLeave,
            String sceneAfterLeave,
            boolean leaveRemoved,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * 返回协议驱动示例运行摘要。
         *
         * @return 摘要行；不可为空；无数据变更；线程安全。
         */
        public String summaryLine() {
            return "rpg-protocol=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|uid=" + uid
                    + "|player=" + playerName
                    + "|position=" + position.x() + "," + position.y()
                    + "|sceneBefore=" + sceneBeforeLeave
                    + "|sceneAfter=" + sceneAfterLeave
                    + "|leaveRemoved=" + leaveRemoved
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
