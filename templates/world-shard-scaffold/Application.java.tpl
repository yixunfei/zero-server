package __PACKAGE__;

import __PACKAGE__.generated.bo.WorldShardEnterWorldEventBO;
import __PACKAGE__.generated.bo.WorldShardMoveEntityEventBO;
import __PACKAGE__.generated.bo.WorldShardQueryEntityEventBO;
import __PACKAGE__.generated.bo.WorldShardTransferShardEventBO;
import __PACKAGE__.generated.dto.WorldShardEnterWorldProtocolDTO;
import __PACKAGE__.generated.dto.WorldShardMoveEntityProtocolDTO;
import __PACKAGE__.generated.dto.WorldShardQueryEntityProtocolDTO;
import __PACKAGE__.generated.dto.WorldShardTransferShardProtocolDTO;
import __PACKAGE__.generated.dto.codec.WorldShardEnterWorldProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.WorldShardMoveEntityProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.WorldShardQueryEntityProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.WorldShardTransferShardProtocolDTOCodec;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Local world shard scaffold application.
 *
 * <p>This class wires generated world shard protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates world shard state and local shard migration without external
 * middleware. It is not a production WorldShard, ScenePartition or migration API.</p>
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
     * World command metric.
     */
    private static final String WORLD_COMMAND_METRIC = "local_world_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local world shard scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol world shard flow.
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
        try (WorldActor worldActor = new WorldActor(
                runtime, runtime.require(LocalRuntimeCapabilities.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(worldActor);

            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_ENTER_WORLD_PROTOCOL,
                    WorldShardEnterWorldProtocolDTOCodec.INSTANCE,
                    enterRequest(1001L, "shard-a", 10, 10, "trace-world-enter-1001"));
            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_ENTER_WORLD_PROTOCOL,
                    WorldShardEnterWorldProtocolDTOCodec.INSTANCE,
                    enterRequest(1002L, "shard-a", 12, 10, "trace-world-enter-1002"));
            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_MOVE_ENTITY_PROTOCOL,
                    WorldShardMoveEntityProtocolDTOCodec.INSTANCE,
                    moveRequest(1001L, 20, 10));
            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_TRANSFER_SHARD_PROTOCOL,
                    WorldShardTransferShardProtocolDTOCodec.INSTANCE,
                    transferRequest(1001L, "shard-b", 52, 3));
            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_MOVE_ENTITY_PROTOCOL,
                    WorldShardMoveEntityProtocolDTOCodec.INSTANCE,
                    moveRequest(1001L, 54, 4));
            dispatch(dispatcher, ProtocolIds.WORLD_SHARD_QUERY_ENTITY_PROTOCOL,
                    WorldShardQueryEntityProtocolDTOCodec.INSTANCE,
                    queryRequest(1001L));

            return new DemoResult(
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    application.config().getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    worldActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            application.stop();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final WorldActor worldActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        WorldBO bo = new WorldBO(worldActor);
        dispatcher.registerWorldShardEnterWorldEventBO(bo);
        dispatcher.registerWorldShardMoveEntityEventBO(bo);
        dispatcher.registerWorldShardTransferShardEventBO(bo);
        dispatcher.registerWorldShardQueryEntityEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                WORLD_COMMAND_METRIC,
                "local world command count",
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

    private static WorldShardEnterWorldProtocolDTO enterRequest(
            final long uid,
            final String shardId,
            final int x,
            final int y,
            final String traceId) {
        WorldShardEnterWorldProtocolDTO request = new WorldShardEnterWorldProtocolDTO();
        request.uid = uid;
        request.worldId = "world-1";
        request.shardId = shardId;
        request.x = x;
        request.y = y;
        request.traceId = traceId;
        return request;
    }

    private static WorldShardMoveEntityProtocolDTO moveRequest(final long uid, final int x, final int y) {
        WorldShardMoveEntityProtocolDTO request = new WorldShardMoveEntityProtocolDTO();
        request.uid = uid;
        request.worldId = "world-1";
        request.x = x;
        request.y = y;
        request.traceId = "trace-world-move-" + uid + "-" + x + "-" + y;
        return request;
    }

    private static WorldShardTransferShardProtocolDTO transferRequest(
            final long uid,
            final String targetShardId,
            final int x,
            final int y) {
        WorldShardTransferShardProtocolDTO request = new WorldShardTransferShardProtocolDTO();
        request.uid = uid;
        request.worldId = "world-1";
        request.targetShardId = targetShardId;
        request.x = x;
        request.y = y;
        request.traceId = "trace-world-transfer-" + uid + "-" + targetShardId;
        return request;
    }

    private static WorldShardQueryEntityProtocolDTO queryRequest(final long uid) {
        WorldShardQueryEntityProtocolDTO request = new WorldShardQueryEntityProtocolDTO();
        request.uid = uid;
        request.worldId = "world-1";
        request.traceId = "trace-world-query-" + uid;
        return request;
    }

    /**
     * Generated world business implementation.
     *
     * @author zn
     */
    private static final class WorldBO implements
            WorldShardEnterWorldEventBO,
            WorldShardMoveEntityEventBO,
            WorldShardTransferShardEventBO,
            WorldShardQueryEntityEventBO {

        /**
         * World Actor facade.
         */
        private final WorldActor worldActor;

        private WorldBO(final WorldActor worldActor) {
            this.worldActor = Objects.requireNonNull(worldActor, "worldActor");
        }

        @Override
        public void enterWorld(final WorldShardEnterWorldProtocolDTO request) {
            worldActor.dispatch(WorldCommand.enter(
                    request.worldId,
                    request.shardId,
                    request.uid,
                    request.x,
                    request.y,
                    request.traceId));
        }

        @Override
        public void moveEntity(final WorldShardMoveEntityProtocolDTO request) {
            worldActor.dispatch(WorldCommand.move(
                    request.worldId,
                    request.uid,
                    request.x,
                    request.y,
                    request.traceId));
        }

        @Override
        public void transferShard(final WorldShardTransferShardProtocolDTO request) {
            worldActor.dispatch(WorldCommand.transfer(
                    request.worldId,
                    request.targetShardId,
                    request.uid,
                    request.x,
                    request.y,
                    request.traceId));
        }

        @Override
        public void queryEntity(final WorldShardQueryEntityProtocolDTO request) {
            worldActor.dispatch(WorldCommand.query(request.worldId, request.uid, request.traceId));
        }
    }

    /**
     * World Actor facade.
     *
     * @author zn
     */
    private static final class WorldActor implements AutoCloseable {

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
         * World store.
         */
        private final WorldStore worldStore = new WorldStore();

        /**
         * Last summary.
         */
        private final AtomicReference<String> summary = new AtomicReference<>("");

        /**
         * Handler subscription.
         */
        private final ActorSubscription subscription;

        private WorldActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(LocalRuntimeCapabilities.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(WorldCommand.class, ActorHandler.sync((context, message) -> {
                WorldCommand command = (WorldCommand) message.payload();
                String current = worldStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final WorldCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.custom("world:" + command.worldId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final WorldCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "world command handled",
                    Map.of("operation", command.operation(), "worldId", command.worldId())));
            monitorRuntime.registry().record(new MetricSample(
                    WORLD_COMMAND_METRIC,
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
     * Local world store.
     *
     * @author zn
     */
    private static final class WorldStore {

        /**
         * Worlds by ID.
         */
        private final Map<String, WorldState> worlds = new LinkedHashMap<>();

        private String apply(final WorldCommand command) {
            WorldState world = worlds.computeIfAbsent(command.worldId(), WorldState::new);
            return switch (command.operation()) {
                case "enter" -> enter(world, command);
                case "move" -> move(world, command);
                case "transfer" -> transfer(world, command);
                case "query" -> query(world, command);
                default -> throw new IllegalArgumentException("unsupported world operation: " + command.operation());
            };
        }

        private String enter(final WorldState world, final WorldCommand command) {
            WorldEntity entity = new WorldEntity(command.uid(), command.shardId(), command.x(), command.y());
            world.entities().put(command.uid(), entity);
            world.shard(command.shardId()).put(command.uid(), entity);
            world.lastAction("enter:" + command.uid() + "@" + command.shardId());
            return world.summary(command.uid());
        }

        private String move(final WorldState world, final WorldCommand command) {
            WorldEntity entity = world.require(command.uid());
            entity.position(command.x(), command.y());
            world.lastAction("move:" + command.uid() + "@" + command.x() + "," + command.y());
            return world.summary(command.uid());
        }

        private String transfer(final WorldState world, final WorldCommand command) {
            WorldEntity entity = world.require(command.uid());
            world.shard(entity.shardId()).remove(command.uid());
            entity.shardId(command.shardId());
            entity.position(command.x(), command.y());
            world.shard(command.shardId()).put(command.uid(), entity);
            world.migrations(world.migrations() + 1);
            world.lastAction("transfer:" + command.uid() + "->" + command.shardId());
            return world.summary(command.uid());
        }

        private String query(final WorldState world, final WorldCommand command) {
            WorldEntity entity = world.require(command.uid());
            world.lastAction("query:" + command.uid() + "@" + entity.shardId());
            return world.summary(command.uid());
        }
    }

    /**
     * World state.
     *
     * @author zn
     */
    private static final class WorldState {

        /**
         * World ID.
         */
        private final String worldId;

        /**
         * Entities by ID.
         */
        private final Map<Long, WorldEntity> entities = new LinkedHashMap<>();

        /**
         * Shards by ID.
         */
        private final Map<String, Map<Long, WorldEntity>> shards = new LinkedHashMap<>();

        /**
         * Migration count.
         */
        private int migrations;

        /**
         * Last action.
         */
        private String lastAction = "";

        private WorldState(final String worldId) {
            this.worldId = Objects.requireNonNull(worldId, "worldId");
        }

        private Map<Long, WorldEntity> entities() {
            return entities;
        }

        private Map<Long, WorldEntity> shard(final String shardId) {
            return shards.computeIfAbsent(shardId, ignored -> new LinkedHashMap<>());
        }

        private WorldEntity require(final long uid) {
            WorldEntity entity = entities.get(uid);
            if (entity == null) {
                throw new IllegalStateException("world entity not found: " + uid);
            }
            return entity;
        }

        private int migrations() {
            return migrations;
        }

        private void migrations(final int migrations) {
            this.migrations = migrations;
        }

        private void lastAction(final String lastAction) {
            this.lastAction = Objects.requireNonNull(lastAction, "lastAction");
        }

        private String summary(final long uid) {
            WorldEntity entity = require(uid);
            return "world=" + worldId
                    + ",shards=" + shards.size()
                    + ",entities=" + entities.size()
                    + ",entity=" + entity.uid()
                    + ",shard=" + entity.shardId()
                    + ",position=" + entity.x() + "," + entity.y()
                    + ",migrations=" + migrations
                    + ",lastAction=" + lastAction;
        }
    }

    /**
     * World entity state.
     *
     * @author zn
     */
    private static final class WorldEntity {

        /**
         * Entity ID.
         */
        private final long uid;

        /**
         * Current shard ID.
         */
        private String shardId;

        /**
         * X coordinate.
         */
        private int x;

        /**
         * Y coordinate.
         */
        private int y;

        private WorldEntity(final long uid, final String shardId, final int x, final int y) {
            this.uid = uid;
            this.shardId = Objects.requireNonNull(shardId, "shardId");
            this.x = x;
            this.y = y;
        }

        private long uid() {
            return uid;
        }

        private String shardId() {
            return shardId;
        }

        private void shardId(final String shardId) {
            this.shardId = Objects.requireNonNull(shardId, "shardId");
        }

        private int x() {
            return x;
        }

        private int y() {
            return y;
        }

        private void position(final int x, final int y) {
            this.x = x;
            this.y = y;
        }
    }

    /**
     * World command.
     *
     * @param operation operation name.
     * @param worldId world ID.
     * @param shardId shard ID.
     * @param uid entity ID.
     * @param x x coordinate.
     * @param y y coordinate.
     * @param traceId trace ID.
     */
    private record WorldCommand(
            String operation,
            String worldId,
            String shardId,
            long uid,
            int x,
            int y,
            String traceId) {

        private static WorldCommand enter(
                final String worldId,
                final String shardId,
                final long uid,
                final int x,
                final int y,
                final String traceId) {
            return new WorldCommand("enter", worldId, shardId, uid, x, y, traceId);
        }

        private static WorldCommand move(
                final String worldId,
                final long uid,
                final int x,
                final int y,
                final String traceId) {
            return new WorldCommand("move", worldId, "", uid, x, y, traceId);
        }

        private static WorldCommand transfer(
                final String worldId,
                final String shardId,
                final long uid,
                final int x,
                final int y,
                final String traceId) {
            return new WorldCommand("transfer", worldId, shardId, uid, x, y, traceId);
        }

        private static WorldCommand query(final String worldId, final long uid, final String traceId) {
            return new WorldCommand("query", worldId, "", uid, 0, 0, traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param worldSummary world summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String worldSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "world-shard=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + worldSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
