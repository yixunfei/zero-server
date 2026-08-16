package group.zn.zero.examples.rpg;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogAppender;
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
import group.zn.zero.scene.LocalSceneService;
import group.zn.zero.scene.SceneEnterRequest;
import group.zn.zero.scene.SceneLeaveRequest;
import group.zn.zero.scene.SceneLeaveResult;
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
import java.util.Optional;

/**
 * RPG 最小本地示例。
 *
 * <p>该示例只使用 local starter、本地玩家服务、本地场景服务、内存日志和内存指标，
 * 不连接外部中间件。它适合作为业务项目理解 zeroServer 组合方式的第一站。</p>
 *
 * @author zn
 */
public final class RpgMinimalApplication {

    /**
     * 示例模块名。
     */
    private static final String MODULE = "rpg-minimal";

    /**
     * 示例固定日志来源。
     */
    private static final LogSource LOG_SOURCE = new LogSource(MODULE, "local", MODULE);

    /**
     * 登录指标名称。
     */
    private static final String LOGIN_METRIC = "zero_example_rpg_login_total";

    /**
     * 加载玩家指标名称。
     */
    private static final String LOAD_PLAYER_METRIC = "zero_example_rpg_player_load_total";

    /**
     * 场景移动指标名称。
     */
    private static final String MOVE_METRIC = "zero_example_rpg_scene_move_total";

    /**
     * GM 查询指标名称。
     */
    private static final String GM_QUERY_METRIC = "zero_example_rpg_gm_query_total";

    /**
     * 离开场景指标名称。
     */
    private static final String LEAVE_SCENE_METRIC = "zero_example_rpg_scene_leave_total";

    private RpgMinimalApplication() {
    }

    /**
     * 启动 RPG 最小本地示例。
     *
     * @param args 命令行参数；当前未使用；可为空。
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * 执行本地 RPG 最小流程。
     *
     * <p>本方法会启动 local runtime，创建玩家和场景本地服务，按 Actor lane 修改玩家与场景状态，
     * 最后停止 runtime。返回值为不可变摘要，不包含敏感信息。</p>
     *
     * @return 示例运行结果；不可为空；线程安全。
     */
    public static DemoResult runDemo() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        GameRuntime components = LocalRuntime.builder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                        ZeroRuntimeConfigKeys.ZERO_NAME, MODULE)),
                terminalLogSink,
                ZeroRuntimeExecutors.localPrototype("zero-example-rpg", 2))
                .replace(LocalRuntimeCapabilities.MONITOR_RUNTIME, monitorRuntime)
                .build();
        ZeroServerApplication application = new ZeroServerApplication(components);

        application.start();
        try (LocalPlayerService playerService = new LocalPlayerService(
                components.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER),
                request -> 1001L);
                LocalSceneService sceneService = new LocalSceneService(
                        components.require(LocalRuntimeCapabilities.ACTOR_SCHEDULER))) {
            registerMetrics(monitorRuntime);
            PlayerLoginResult login = playerService.login(new PlayerLoginRequest(
                    "guest-1001",
                    "local-token",
                    "trace-rpg-login")).toCompletableFuture().join();
            record(components.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime,
                    "trace-rpg-login", "player login completed", LOGIN_METRIC, "login");

            PlayerProfile profile = playerService.loadPlayer(new PlayerLoadRequest(
                    login.uid(),
                    "trace-rpg-load")).toCompletableFuture().join();
            record(components.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime,
                    "trace-rpg-load", "player profile loaded", LOAD_PLAYER_METRIC, "load");

            sceneService.enterScene(new SceneEnterRequest(
                    login.uid(),
                    "scene-1",
                    "trace-rpg-enter")).toCompletableFuture().join();

            SceneMoveResult move = sceneService.moveWithResult(new SceneMoveRequest(
                    login.uid(),
                    "scene-1",
                    new ScenePosition(7, 11),
                    "trace-rpg-move")).toCompletableFuture().join();
            record(components.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime,
                    "trace-rpg-move", "scene move completed", MOVE_METRIC, "move");

            Optional<PlayerProfile> gmPlayer = playerService.queryPlayer(
                    login.uid(),
                    "trace-rpg-gm-player").toCompletableFuture().join();
            List<?> sceneBeforeLeave = sceneService.listEntities(
                    "scene-1",
                    "trace-rpg-gm-scene-before").toCompletableFuture().join();
            record(components.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime,
                    "trace-rpg-gm", "gm query completed", GM_QUERY_METRIC, "gm-query");

            SceneLeaveResult leave = sceneService.leaveScene(new SceneLeaveRequest(
                    login.uid(),
                    "scene-1",
                    "trace-rpg-leave")).toCompletableFuture().join();
            List<?> sceneAfterLeave = sceneService.listEntities(
                    "scene-1",
                    "trace-rpg-gm-scene-after").toCompletableFuture().join();
            record(components.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime,
                    "trace-rpg-leave", "scene leave completed", LEAVE_SCENE_METRIC, "leave");

            Map<String, String> runtimeConfig = components.require(LocalRuntimeCapabilities.CONFIG).asMap();
            return new DemoResult(
                    runtimeConfig.get(ZeroRuntimeConfigKeys.ZERO_MODE),
                    runtimeConfig.get(ZeroRuntimeConfigKeys.ZERO_NAME),
                    login.uid(),
                    gmPlayer.orElse(profile).name(),
                    move.currentState().position(),
                    sceneBeforeLeave.size(),
                    sceneAfterLeave.size(),
                    leave.removed(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size());
        } finally {
            application.stop();
        }
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                LOGIN_METRIC, "RPG example login count", "count", List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                LOAD_PLAYER_METRIC,
                "RPG example player load count",
                "count",
                List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                MOVE_METRIC, "RPG example scene move count", "count", List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                GM_QUERY_METRIC, "RPG example GM query count", "count", List.of("action")));
        monitorRuntime.registry().register(new MetricDefinition(
                LEAVE_SCENE_METRIC,
                "RPG example scene leave count",
                "count",
                List.of("action")));
    }

    private static void record(
            final LogAppender logAppender,
            final MonitorRuntime monitorRuntime,
            final String traceId,
            final String message,
            final String metricName,
            final String action) {
        logAppender.append(ZeroLogRecord.create(
                Instant.now(),
                LogLevel.INFO,
                LogType.BUSINESS,
                LOG_SOURCE,
                new LogOperation(action, LogResult.SUCCESS, null),
                traceId,
                message,
                Map.of("action", action)));
        monitorRuntime.registry().record(new MetricSample(
                metricName,
                1D,
                Map.of("action", action),
                Instant.now()));
    }

    /**
     * 示例运行结果。
     *
     * @param mode runtime 模式。
     * @param name runtime 名称。
     * @param uid 玩家 ID。
     * @param playerName 玩家名。
     * @param position 移动后的场景坐标。
     * @param sceneBeforeLeave 离开场景前实体数量。
     * @param sceneAfterLeave 离开场景后实体数量。
     * @param leaveRemoved 离开场景时是否移除了实体。
     * @param logCount 日志数量。
     * @param metricCount 指标样本数量。
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            long uid,
            String playerName,
            ScenePosition position,
            int sceneBeforeLeave,
            int sceneAfterLeave,
            boolean leaveRemoved,
            int logCount,
            int metricCount) {

        /**
         * 返回示例运行摘要行。
         *
         * @return 摘要行；不可为空；无数据变更；线程安全。
         */
        public String summaryLine() {
            return "rpg-minimal=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|uid=" + uid
                    + "|player=" + playerName
                    + "|position=" + position.x() + "," + position.y()
                    + "|sceneBefore=" + sceneBeforeLeave
                    + "|sceneAfter=" + sceneAfterLeave
                    + "|leaveRemoved=" + leaveRemoved
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount;
        }
    }
}
