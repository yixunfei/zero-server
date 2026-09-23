package __PACKAGE__;

import __PACKAGE__.generated.bo.NpcTickQueryNpcEventBO;
import __PACKAGE__.generated.bo.NpcTickSetBehaviorEventBO;
import __PACKAGE__.generated.bo.NpcTickSpawnNpcEventBO;
import __PACKAGE__.generated.bo.NpcTickTickZoneEventBO;
import __PACKAGE__.generated.dto.codec.NpcTickQueryNpcProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.NpcTickSetBehaviorProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.NpcTickSpawnNpcProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.NpcTickTickZoneProtocolDTOCodec;
import __PACKAGE__.generated.dto.NpcTickQueryNpcProtocolDTO;
import __PACKAGE__.generated.dto.NpcTickSetBehaviorProtocolDTO;
import __PACKAGE__.generated.dto.NpcTickSpawnNpcProtocolDTO;
import __PACKAGE__.generated.dto.NpcTickTickZoneProtocolDTO;
import __PACKAGE__.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ActorSubscription;
import group.zn.zero.core.config.ZeroConfigLoader;
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
import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Local NPC tick scaffold application.
 *
 * <p>This class wires generated NPC tick protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates NPC lifecycle and deterministic local ticks without external
 * middleware. It is not a production NPC, AI or tick scheduling API.</p>
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

    /**
     * NPC command metric.
     */
    private static final String NPC_COMMAND_METRIC = "local_npc_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local NPC tick scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol NPC tick flow.
     *
     * @return demo result; never null; thread-safe.
     */
    public static DemoResult runDemo() {
        InMemoryLogSink terminalLogSink = new InMemoryLogSink();
        GameRuntime runtime = RuntimeAssembly.create(
                ZeroConfigLoader.loadStandard(Map.of(
                        __CONFIG_DEFAULTS__,
                        ZeroRuntimeConfigKeys.ZERO_NAME, APP_NAME)),
                terminalLogSink);
        MonitorRuntime monitorRuntime = runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME);

        runtime.start();
        runtime.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                Instant.now(), LogLevel.INFO, LogType.RUNTIME, LOG_SOURCE,
                new LogOperation("runtime-start", LogResult.SUCCESS, null),
                "bootstrap", "zeroServer started", Map.of("name", APP_NAME)));
        try (NpcActor npcActor = new NpcActor(
                runtime, runtime.require(LogRuntime.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(npcActor);

            dispatch(dispatcher, ProtocolIds.NPC_TICK_SPAWN_NPC_PROTOCOL,
                    NpcTickSpawnNpcProtocolDTOCodec.INSTANCE, spawnRequest());
            dispatch(dispatcher, ProtocolIds.NPC_TICK_SET_BEHAVIOR_PROTOCOL,
                    NpcTickSetBehaviorProtocolDTOCodec.INSTANCE, behaviorRequest("patrol", "trace-npc-patrol"));
            dispatch(dispatcher, ProtocolIds.NPC_TICK_TICK_ZONE_PROTOCOL,
                    NpcTickTickZoneProtocolDTOCodec.INSTANCE, tickRequest(1));
            dispatch(dispatcher, ProtocolIds.NPC_TICK_TICK_ZONE_PROTOCOL,
                    NpcTickTickZoneProtocolDTOCodec.INSTANCE, tickRequest(2));
            dispatch(dispatcher, ProtocolIds.NPC_TICK_SET_BEHAVIOR_PROTOCOL,
                    NpcTickSetBehaviorProtocolDTOCodec.INSTANCE, behaviorRequest("idle", "trace-npc-idle"));
            dispatch(dispatcher, ProtocolIds.NPC_TICK_TICK_ZONE_PROTOCOL,
                    NpcTickTickZoneProtocolDTOCodec.INSTANCE, tickRequest(3));
            dispatch(dispatcher, ProtocolIds.NPC_TICK_QUERY_NPC_PROTOCOL,
                    NpcTickQueryNpcProtocolDTOCodec.INSTANCE, queryRequest());

            return new DemoResult(
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    npcActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            runtime.close();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final NpcActor npcActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        NpcBO bo = new NpcBO(npcActor);
        dispatcher.registerNpcTickSpawnNpcEventBO(bo);
        dispatcher.registerNpcTickSetBehaviorEventBO(bo);
        dispatcher.registerNpcTickTickZoneEventBO(bo);
        dispatcher.registerNpcTickQueryNpcEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                NPC_COMMAND_METRIC,
                "local NPC command count",
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

    private static NpcTickSpawnNpcProtocolDTO spawnRequest() {
        NpcTickSpawnNpcProtocolDTO request = new NpcTickSpawnNpcProtocolDTO();
        request.npcId = 2001L;
        request.zoneId = "zone-1";
        request.x = 10;
        request.y = 10;
        request.traceId = "trace-npc-spawn";
        return request;
    }

    private static NpcTickSetBehaviorProtocolDTO behaviorRequest(final String behavior, final String traceId) {
        NpcTickSetBehaviorProtocolDTO request = new NpcTickSetBehaviorProtocolDTO();
        request.npcId = 2001L;
        request.zoneId = "zone-1";
        request.behavior = behavior;
        request.traceId = traceId;
        return request;
    }

    private static NpcTickTickZoneProtocolDTO tickRequest(final int tick) {
        NpcTickTickZoneProtocolDTO request = new NpcTickTickZoneProtocolDTO();
        request.zoneId = "zone-1";
        request.tick = tick;
        request.traceId = "trace-npc-tick-" + tick;
        return request;
    }

    private static NpcTickQueryNpcProtocolDTO queryRequest() {
        NpcTickQueryNpcProtocolDTO request = new NpcTickQueryNpcProtocolDTO();
        request.npcId = 2001L;
        request.zoneId = "zone-1";
        request.traceId = "trace-npc-query";
        return request;
    }

    /**
     * Generated NPC business implementation.
     *
     * @author zn
     */
    private static final class NpcBO implements
            NpcTickSpawnNpcEventBO,
            NpcTickSetBehaviorEventBO,
            NpcTickTickZoneEventBO,
            NpcTickQueryNpcEventBO {

        /**
         * NPC Actor facade.
         */
        private final NpcActor npcActor;

        private NpcBO(final NpcActor npcActor) {
            this.npcActor = Objects.requireNonNull(npcActor, "npcActor");
        }

        @Override
        public void spawnNpc(final NpcTickSpawnNpcProtocolDTO request) {
            npcActor.dispatch(NpcCommand.spawn(
                    request.zoneId,
                    request.npcId,
                    request.x,
                    request.y,
                    request.traceId));
        }

        @Override
        public void setBehavior(final NpcTickSetBehaviorProtocolDTO request) {
            npcActor.dispatch(NpcCommand.behavior(
                    request.zoneId,
                    request.npcId,
                    request.behavior,
                    request.traceId));
        }

        @Override
        public void tickZone(final NpcTickTickZoneProtocolDTO request) {
            npcActor.dispatch(NpcCommand.tick(request.zoneId, request.tick, request.traceId));
        }

        @Override
        public void queryNpc(final NpcTickQueryNpcProtocolDTO request) {
            npcActor.dispatch(NpcCommand.query(request.zoneId, request.npcId, request.traceId));
        }
    }

    /**
     * NPC Actor facade.
     *
     * @author zn
     */
    private static final class NpcActor implements AutoCloseable {

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
         * NPC zone store.
         */
        private final NpcStore npcStore = new NpcStore();

        /**
         * Last summary.
         */
        private final AtomicReference<String> summary = new AtomicReference<>("");

        /**
         * Handler subscription.
         */
        private final ActorSubscription subscription;

        private NpcActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(ActorRuntime.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(NpcCommand.class, ActorHandler.sync((context, message) -> {
                NpcCommand command = (NpcCommand) message.payload();
                String current = npcStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final NpcCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.custom("zone:" + command.zoneId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final NpcCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "NPC command handled",
                    Map.of("operation", command.operation(), "zoneId", command.zoneId())));
            monitorRuntime.registry().record(new MetricSample(
                    NPC_COMMAND_METRIC,
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
     * Local NPC store.
     *
     * @author zn
     */
    private static final class NpcStore {

        /**
         * Zones by ID.
         */
        private final Map<String, ZoneState> zones = new LinkedHashMap<>();

        private String apply(final NpcCommand command) {
            ZoneState zone = zones.computeIfAbsent(command.zoneId(), ZoneState::new);
            return switch (command.operation()) {
                case "spawn" -> spawn(zone, command);
                case "behavior" -> behavior(zone, command);
                case "tick" -> tick(zone, command);
                case "query" -> query(zone, command);
                default -> throw new IllegalArgumentException("unsupported NPC operation: " + command.operation());
            };
        }

        private String spawn(final ZoneState zone, final NpcCommand command) {
            NpcState npc = new NpcState(command.npcId(), command.x(), command.y());
            npc.markAction("spawn:" + command.npcId() + "@" + command.x() + "," + command.y());
            zone.npcs().put(command.npcId(), npc);
            return zone.summary(command.npcId());
        }

        private String behavior(final ZoneState zone, final NpcCommand command) {
            NpcState npc = zone.require(command.npcId());
            npc.behavior(command.behavior());
            npc.markAction("behavior:" + command.npcId() + "=" + command.behavior());
            return zone.summary(command.npcId());
        }

        private String tick(final ZoneState zone, final NpcCommand command) {
            for (NpcState npc : zone.npcs().values()) {
                npc.tick(command.tick());
            }
            zone.currentTick(command.tick());
            return zone.summary(zone.firstNpcId());
        }

        private String query(final ZoneState zone, final NpcCommand command) {
            NpcState npc = zone.require(command.npcId());
            npc.markAction("query:" + command.npcId() + "@" + npc.x() + "," + npc.y());
            return zone.summary(command.npcId());
        }
    }

    /**
     * Zone state.
     *
     * @author zn
     */
    private static final class ZoneState {

        /**
         * Zone ID.
         */
        private final String zoneId;

        /**
         * NPC map.
         */
        private final Map<Long, NpcState> npcs = new LinkedHashMap<>();

        /**
         * Current tick.
         */
        private int currentTick;

        private ZoneState(final String zoneId) {
            this.zoneId = Objects.requireNonNull(zoneId, "zoneId");
        }

        private Map<Long, NpcState> npcs() {
            return npcs;
        }

        private long firstNpcId() {
            if (npcs.isEmpty()) {
                throw new IllegalStateException("zone has no NPC: " + zoneId);
            }
            return npcs.keySet().iterator().next();
        }

        private NpcState require(final long npcId) {
            NpcState npc = npcs.get(npcId);
            if (npc == null) {
                throw new IllegalStateException("NPC not found: " + npcId);
            }
            return npc;
        }

        private void currentTick(final int currentTick) {
            this.currentTick = currentTick;
        }

        private String summary(final long npcId) {
            NpcState npc = require(npcId);
            return "zone=" + zoneId
                    + ",npcs=" + npcs.size()
                    + ",npc=" + npc.npcId()
                    + ",behavior=" + npc.behavior()
                    + ",position=" + npc.x() + "," + npc.y()
                    + ",tick=" + currentTick
                    + ",updates=" + npc.updates()
                    + ",lastAction=" + npc.lastAction();
        }
    }

    /**
     * NPC state.
     *
     * @author zn
     */
    private static final class NpcState {

        /**
         * NPC ID.
         */
        private final long npcId;

        /**
         * X coordinate.
         */
        private int x;

        /**
         * Y coordinate.
         */
        private int y;

        /**
         * Current behavior.
         */
        private String behavior = "idle";

        /**
         * Update count.
         */
        private int updates;

        /**
         * Last action.
         */
        private String lastAction = "";

        private NpcState(final long npcId, final int x, final int y) {
            this.npcId = npcId;
            this.x = x;
            this.y = y;
        }

        private long npcId() {
            return npcId;
        }

        private int x() {
            return x;
        }

        private int y() {
            return y;
        }

        private String behavior() {
            return behavior;
        }

        private void behavior(final String behavior) {
            if (!"idle".equals(behavior) && !"patrol".equals(behavior)) {
                throw new IllegalArgumentException("unsupported NPC behavior: " + behavior);
            }
            this.behavior = behavior;
        }

        private int updates() {
            return updates;
        }

        private String lastAction() {
            return lastAction;
        }

        private void tick(final int tick) {
            if ("patrol".equals(behavior)) {
                x++;
            }
            markAction("tick:" + tick + ":" + behavior + "@" + x + "," + y);
        }

        private void markAction(final String action) {
            updates++;
            lastAction = Objects.requireNonNull(action, "action");
        }
    }

    /**
     * NPC command.
     *
     * @param operation operation name.
     * @param zoneId zone ID.
     * @param npcId NPC ID.
     * @param x x coordinate.
     * @param y y coordinate.
     * @param tick tick number.
     * @param behavior behavior name.
     * @param traceId trace ID.
     */
    private record NpcCommand(
            String operation,
            String zoneId,
            long npcId,
            int x,
            int y,
            int tick,
            String behavior,
            String traceId) {

        private static NpcCommand spawn(
                final String zoneId,
                final long npcId,
                final int x,
                final int y,
                final String traceId) {
            return new NpcCommand("spawn", zoneId, npcId, x, y, 0, "", traceId);
        }

        private static NpcCommand behavior(
                final String zoneId,
                final long npcId,
                final String behavior,
                final String traceId) {
            return new NpcCommand("behavior", zoneId, npcId, 0, 0, 0, behavior, traceId);
        }

        private static NpcCommand tick(final String zoneId, final int tick, final String traceId) {
            return new NpcCommand("tick", zoneId, 0L, 0, 0, tick, "", traceId);
        }

        private static NpcCommand query(final String zoneId, final long npcId, final String traceId) {
            return new NpcCommand("query", zoneId, npcId, 0, 0, 0, "", traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param npcSummary NPC summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String npcSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "npc-tick=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + npcSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
