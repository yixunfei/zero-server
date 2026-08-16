package __PACKAGE__;

import __PACKAGE__.generated.bo.SceneSyncEnterSceneEventBO;
import __PACKAGE__.generated.bo.SceneSyncMoveEventBO;
import __PACKAGE__.generated.bo.SceneSyncQueryVisibleEventBO;
import __PACKAGE__.generated.dto.SceneSyncEnterSceneProtocolDTO;
import __PACKAGE__.generated.dto.SceneSyncMoveProtocolDTO;
import __PACKAGE__.generated.dto.SceneSyncQueryVisibleProtocolDTO;
import __PACKAGE__.generated.dto.codec.SceneSyncEnterSceneProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.SceneSyncMoveProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.SceneSyncQueryVisibleProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
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
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroPayloadCodec;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.starter.LocalRuntime;
import group.zn.zero.starter.LocalRuntimeCapabilities;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import group.zn.zero.starter.ZeroRuntimeExecutors;
import group.zn.zero.starter.ZeroServerApplication;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/**
 * Local scene sync scaffold application.
 *
 * <p>This class wires generated scene-sync protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates small-scale scene state and visibility queries without external
 * middleware. It is not a production AOI or state-sync API.</p>
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
     * Scene command metric.
     */
    private static final String SCENE_COMMAND_METRIC = "local_scene_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local scene sync scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol scene sync flow.
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
        try (SceneSyncActor sceneActor = new SceneSyncActor(
                runtime, runtime.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(sceneActor);

            dispatch(dispatcher, ProtocolIds.SCENE_SYNC_ENTER_SCENE_PROTOCOL,
                    SceneSyncEnterSceneProtocolDTOCodec.INSTANCE, enterRequest(1001L, 5, 5, 4, "trace-scene-enter-1"));
            dispatch(dispatcher, ProtocolIds.SCENE_SYNC_ENTER_SCENE_PROTOCOL,
                    SceneSyncEnterSceneProtocolDTOCodec.INSTANCE, enterRequest(1002L, 7, 8, 4, "trace-scene-enter-2"));
            dispatch(dispatcher, ProtocolIds.SCENE_SYNC_ENTER_SCENE_PROTOCOL,
                    SceneSyncEnterSceneProtocolDTOCodec.INSTANCE, enterRequest(1003L, 20, 20, 4, "trace-scene-enter-3"));
            dispatch(dispatcher, ProtocolIds.SCENE_SYNC_MOVE_PROTOCOL,
                    SceneSyncMoveProtocolDTOCodec.INSTANCE, moveRequest(1003L, 8, 8));
            dispatch(dispatcher, ProtocolIds.SCENE_SYNC_QUERY_VISIBLE_PROTOCOL,
                    SceneSyncQueryVisibleProtocolDTOCodec.INSTANCE, queryVisibleRequest(1001L));

            return new DemoResult(
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    sceneActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            application.stop();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final SceneSyncActor sceneActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        SceneSyncBO bo = new SceneSyncBO(sceneActor);
        dispatcher.registerSceneSyncEnterSceneEventBO(bo);
        dispatcher.registerSceneSyncMoveEventBO(bo);
        dispatcher.registerSceneSyncQueryVisibleEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                SCENE_COMMAND_METRIC,
                "local scene command count",
                "count",
                List.of("operation")));
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

    private static SceneSyncEnterSceneProtocolDTO enterRequest(
            final long uid,
            final int x,
            final int y,
            final int viewRange,
            final String traceId) {
        SceneSyncEnterSceneProtocolDTO request = new SceneSyncEnterSceneProtocolDTO();
        request.uid = uid;
        request.sceneId = "scene-1";
        request.x = x;
        request.y = y;
        request.viewRange = viewRange;
        request.traceId = traceId;
        return request;
    }

    private static SceneSyncMoveProtocolDTO moveRequest(final long uid, final int x, final int y) {
        SceneSyncMoveProtocolDTO request = new SceneSyncMoveProtocolDTO();
        request.uid = uid;
        request.sceneId = "scene-1";
        request.x = x;
        request.y = y;
        request.traceId = "trace-scene-move-" + uid;
        return request;
    }

    private static SceneSyncQueryVisibleProtocolDTO queryVisibleRequest(final long uid) {
        SceneSyncQueryVisibleProtocolDTO request = new SceneSyncQueryVisibleProtocolDTO();
        request.uid = uid;
        request.sceneId = "scene-1";
        request.traceId = "trace-scene-query-" + uid;
        return request;
    }

    /**
     * Generated scene-sync business implementation.
     *
     * @author zn
     */
    private static final class SceneSyncBO implements
            SceneSyncEnterSceneEventBO,
            SceneSyncMoveEventBO,
            SceneSyncQueryVisibleEventBO {

        /**
         * Scene Actor facade.
         */
        private final SceneSyncActor sceneActor;

        private SceneSyncBO(final SceneSyncActor sceneActor) {
            this.sceneActor = Objects.requireNonNull(sceneActor, "sceneActor");
        }

        @Override
        public void enterScene(final SceneSyncEnterSceneProtocolDTO request) {
            sceneActor.dispatch(SceneCommand.enter(
                    request.sceneId,
                    request.uid,
                    request.x,
                    request.y,
                    request.viewRange,
                    request.traceId));
        }

        @Override
        public void move(final SceneSyncMoveProtocolDTO request) {
            sceneActor.dispatch(SceneCommand.move(
                    request.sceneId,
                    request.uid,
                    request.x,
                    request.y,
                    request.traceId));
        }

        @Override
        public void queryVisible(final SceneSyncQueryVisibleProtocolDTO request) {
            sceneActor.dispatch(SceneCommand.query(request.sceneId, request.uid, request.traceId));
        }
    }

    /**
     * Scene sync Actor facade.
     *
     * @author zn
     */
    private static final class SceneSyncActor implements AutoCloseable {

        /**
         * Actor scheduler.
         */
        private final ActorScheduler scheduler;

        /**
         * Safe log appender.
         */
        private final LogAppender logAppender;

        /**
         * Monitor runtime.
         */
        private final MonitorRuntime monitorRuntime;

        /**
         * Scene store.
         */
        private final SceneStore sceneStore = new SceneStore();

        /**
         * Last summary.
         */
        private final AtomicReference<String> summary = new AtomicReference<>("");

        /**
         * Handler subscription.
         */
        private final ActorSubscription subscription;

        private SceneSyncActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(LocalRuntimeCapabilities.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(SceneCommand.class, ActorHandler.sync((context, message) -> {
                SceneCommand command = (SceneCommand) message.payload();
                String current = sceneStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final SceneCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.scene(command.sceneId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final SceneCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "scene command handled",
                    Map.of("operation", command.operation(), "sceneId", command.sceneId())));
            monitorRuntime.registry().record(new MetricSample(
                    SCENE_COMMAND_METRIC,
                    1D,
                    Map.of("operation", command.operation()),
                    Instant.now()));
        }

        @Override
        public void close() {
            subscription.close();
        }
    }

    /**
     * Local scene store.
     *
     * @author zn
     */
    private static final class SceneStore {

        /**
         * Scenes by ID.
         */
        private final Map<String, SceneState> scenes = new LinkedHashMap<>();

        private String apply(final SceneCommand command) {
            SceneState state = scenes.computeIfAbsent(command.sceneId(), SceneState::new);
            return switch (command.operation()) {
                case "enter" -> enter(state, command);
                case "move" -> move(state, command);
                case "query" -> query(state, command);
                default -> throw new IllegalArgumentException("unsupported scene operation: " + command.operation());
            };
        }

        private String enter(final SceneState state, final SceneCommand command) {
            state.entities().put(command.uid(), new EntityState(
                    command.uid(),
                    command.x(),
                    command.y(),
                    command.viewRange()));
            state.lastDelta("enter:" + command.uid() + "@" + command.x() + "," + command.y());
            return state.summary(command.uid(), List.of());
        }

        private String move(final SceneState state, final SceneCommand command) {
            EntityState entity = state.require(command.uid());
            entity.position(command.x(), command.y());
            state.lastDelta("move:" + command.uid() + "@" + command.x() + "," + command.y());
            return state.summary(command.uid(), List.of());
        }

        private String query(final SceneState state, final SceneCommand command) {
            EntityState focus = state.require(command.uid());
            List<EntityState> visible = new ArrayList<>();
            for (EntityState entity : state.entities().values()) {
                if (entity.uid() != focus.uid() && focus.canSee(entity)) {
                    visible.add(entity);
                }
            }
            return state.summary(command.uid(), visible);
        }
    }

    /**
     * Scene state.
     *
     * @author zn
     */
    private static final class SceneState {

        private final String sceneId;

        private final Map<Long, EntityState> entities = new LinkedHashMap<>();

        private String lastDelta = "";

        private SceneState(final String sceneId) {
            this.sceneId = Objects.requireNonNull(sceneId, "sceneId");
        }

        private Map<Long, EntityState> entities() {
            return entities;
        }

        private EntityState require(final long uid) {
            EntityState entity = entities.get(uid);
            if (entity == null) {
                throw new IllegalStateException("entity not found: " + uid);
            }
            return entity;
        }

        private void lastDelta(final String lastDelta) {
            this.lastDelta = Objects.requireNonNull(lastDelta, "lastDelta");
        }

        private String summary(final long focusUid, final List<EntityState> visible) {
            String visibleIds = visible.stream()
                    .map(entity -> Long.toString(entity.uid()))
                    .collect(Collectors.joining(","));
            return "scene=" + sceneId
                    + ",entities=" + entities.size()
                    + ",focus=" + focusUid
                    + ",visible=" + visible.size()
                    + ",visibleIds=" + visibleIds
                    + ",lastDelta=" + lastDelta;
        }
    }

    /**
     * Entity state.
     *
     * @author zn
     */
    private static final class EntityState {

        private final long uid;

        private int x;

        private int y;

        private final int viewRange;

        private EntityState(final long uid, final int x, final int y, final int viewRange) {
            this.uid = uid;
            this.x = x;
            this.y = y;
            this.viewRange = viewRange;
        }

        private long uid() {
            return uid;
        }

        private void position(final int x, final int y) {
            this.x = x;
            this.y = y;
        }

        private boolean canSee(final EntityState other) {
            return Math.max(Math.abs(x - other.x), Math.abs(y - other.y)) <= viewRange;
        }
    }

    /**
     * Scene command.
     *
     * @param operation operation name.
     * @param sceneId scene ID.
     * @param uid entity ID.
     * @param x target x.
     * @param y target y.
     * @param viewRange visibility range.
     * @param traceId trace ID.
     */
    private record SceneCommand(
            String operation,
            String sceneId,
            long uid,
            int x,
            int y,
            int viewRange,
            String traceId) {

        private static SceneCommand enter(
                final String sceneId,
                final long uid,
                final int x,
                final int y,
                final int viewRange,
                final String traceId) {
            return new SceneCommand("enter", sceneId, uid, x, y, viewRange, traceId);
        }

        private static SceneCommand move(
                final String sceneId,
                final long uid,
                final int x,
                final int y,
                final String traceId) {
            return new SceneCommand("move", sceneId, uid, x, y, 0, traceId);
        }

        private static SceneCommand query(final String sceneId, final long uid, final String traceId) {
            return new SceneCommand("query", sceneId, uid, 0, 0, 0, traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param sceneSummary scene summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String sceneSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "scene-sync=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + sceneSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
