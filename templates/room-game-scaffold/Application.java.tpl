package __PACKAGE__;

import __PACKAGE__.generated.bo.RoomCreateRoomEventBO;
import __PACKAGE__.generated.bo.RoomJoinRoomEventBO;
import __PACKAGE__.generated.bo.RoomReadyEventBO;
import __PACKAGE__.generated.bo.RoomStartMatchEventBO;
import __PACKAGE__.generated.bo.RoomSubmitFrameEventBO;
import __PACKAGE__.generated.dto.codec.RoomCreateRoomProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RoomJoinRoomProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RoomReadyProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RoomStartMatchProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RoomSubmitFrameProtocolDTOCodec;
import __PACKAGE__.generated.dto.RoomCreateRoomProtocolDTO;
import __PACKAGE__.generated.dto.RoomJoinRoomProtocolDTO;
import __PACKAGE__.generated.dto.RoomReadyProtocolDTO;
import __PACKAGE__.generated.dto.RoomStartMatchProtocolDTO;
import __PACKAGE__.generated.dto.RoomSubmitFrameProtocolDTO;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Local room scaffold application.
 *
 * <p>This class wires generated room protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates room-state serialization without external middleware.
 * It is not a production room API.</p>
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
     * Room command metric.
     */
    private static final String ROOM_COMMAND_METRIC = "local_room_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local room scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol room flow.
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
        try (RoomActor roomActor = new RoomActor(
                runtime, runtime.require(LogRuntime.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(roomActor);

            dispatch(dispatcher, ProtocolIds.ROOM_CREATE_ROOM_PROTOCOL,
                    RoomCreateRoomProtocolDTOCodec.INSTANCE, createRoomRequest());
            dispatch(dispatcher, ProtocolIds.ROOM_JOIN_ROOM_PROTOCOL,
                    RoomJoinRoomProtocolDTOCodec.INSTANCE, joinRoomRequest());
            dispatch(dispatcher, ProtocolIds.ROOM_READY_PROTOCOL,
                    RoomReadyProtocolDTOCodec.INSTANCE, readyRequest(1001L, "trace-room-ready-owner"));
            dispatch(dispatcher, ProtocolIds.ROOM_READY_PROTOCOL,
                    RoomReadyProtocolDTOCodec.INSTANCE, readyRequest(1002L, "trace-room-ready-guest"));
            dispatch(dispatcher, ProtocolIds.ROOM_START_MATCH_PROTOCOL,
                    RoomStartMatchProtocolDTOCodec.INSTANCE, startMatchRequest());
            dispatch(dispatcher, ProtocolIds.ROOM_SUBMIT_FRAME_PROTOCOL,
                    RoomSubmitFrameProtocolDTOCodec.INSTANCE, submitFrameRequest());

            return new DemoResult(
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    roomActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            runtime.close();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final RoomActor roomActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        RoomBO bo = new RoomBO(roomActor);
        dispatcher.registerRoomCreateRoomEventBO(bo);
        dispatcher.registerRoomJoinRoomEventBO(bo);
        dispatcher.registerRoomReadyEventBO(bo);
        dispatcher.registerRoomStartMatchEventBO(bo);
        dispatcher.registerRoomSubmitFrameEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                ROOM_COMMAND_METRIC,
                "local room command count",
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

    private static RoomCreateRoomProtocolDTO createRoomRequest() {
        RoomCreateRoomProtocolDTO request = new RoomCreateRoomProtocolDTO();
        request.ownerUid = 1001L;
        request.roomId = "room-1";
        request.traceId = "trace-room-create";
        return request;
    }

    private static RoomJoinRoomProtocolDTO joinRoomRequest() {
        RoomJoinRoomProtocolDTO request = new RoomJoinRoomProtocolDTO();
        request.uid = 1002L;
        request.roomId = "room-1";
        request.traceId = "trace-room-join";
        return request;
    }

    private static RoomReadyProtocolDTO readyRequest(final long uid, final String traceId) {
        RoomReadyProtocolDTO request = new RoomReadyProtocolDTO();
        request.uid = uid;
        request.roomId = "room-1";
        request.traceId = traceId;
        return request;
    }

    private static RoomStartMatchProtocolDTO startMatchRequest() {
        RoomStartMatchProtocolDTO request = new RoomStartMatchProtocolDTO();
        request.roomId = "room-1";
        request.traceId = "trace-room-start";
        return request;
    }

    private static RoomSubmitFrameProtocolDTO submitFrameRequest() {
        RoomSubmitFrameProtocolDTO request = new RoomSubmitFrameProtocolDTO();
        request.uid = 1001L;
        request.roomId = "room-1";
        request.frame = 1;
        request.input = 42;
        request.traceId = "trace-room-frame";
        return request;
    }

    /**
     * Generated room business implementation.
     *
     * @author zn
     */
    private static final class RoomBO implements
            RoomCreateRoomEventBO,
            RoomJoinRoomEventBO,
            RoomReadyEventBO,
            RoomStartMatchEventBO,
            RoomSubmitFrameEventBO {

        /**
         * Room Actor facade.
         */
        private final RoomActor roomActor;

        private RoomBO(final RoomActor roomActor) {
            this.roomActor = Objects.requireNonNull(roomActor, "roomActor");
        }

        @Override
        public void createRoom(final RoomCreateRoomProtocolDTO request) {
            roomActor.dispatch(RoomCommand.createRoom(request.roomId, request.ownerUid, request.traceId));
        }

        @Override
        public void joinRoom(final RoomJoinRoomProtocolDTO request) {
            roomActor.dispatch(RoomCommand.joinRoom(request.roomId, request.uid, request.traceId));
        }

        @Override
        public void ready(final RoomReadyProtocolDTO request) {
            roomActor.dispatch(RoomCommand.ready(request.roomId, request.uid, request.traceId));
        }

        @Override
        public void startMatch(final RoomStartMatchProtocolDTO request) {
            roomActor.dispatch(RoomCommand.startMatch(request.roomId, request.traceId));
        }

        @Override
        public void submitFrame(final RoomSubmitFrameProtocolDTO request) {
            roomActor.dispatch(RoomCommand.submitFrame(
                    request.roomId,
                    request.uid,
                    request.frame,
                    request.input,
                    request.traceId));
        }
    }

    /**
     * Room Actor facade.
     *
     * @author zn
     */
    private static final class RoomActor implements AutoCloseable {

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
         * Room state store.
         */
        private final RoomStore roomStore = new RoomStore();

        /**
         * Last summary.
         */
        private final AtomicReference<String> summary = new AtomicReference<>("");

        /**
         * Handler subscription.
         */
        private final ActorSubscription subscription;

        private RoomActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(ActorRuntime.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(RoomCommand.class, ActorHandler.sync((context, message) -> {
                RoomCommand command = (RoomCommand) message.payload();
                String current = roomStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final RoomCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.custom("room:" + command.roomId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final RoomCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "room command handled",
                    Map.of("operation", command.operation(), "roomId", command.roomId())));
            monitorRuntime.registry().record(new MetricSample(
                    ROOM_COMMAND_METRIC,
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
     * Local room store.
     *
     * @author zn
     */
    private static final class RoomStore {

        /**
         * Rooms by ID.
         */
        private final Map<String, RoomState> rooms = new LinkedHashMap<>();

        private String apply(final RoomCommand command) {
            RoomState state = switch (command.operation()) {
                case "create" -> create(command);
                case "join" -> join(command);
                case "ready" -> ready(command);
                case "start" -> start(command);
                case "frame" -> frame(command);
                default -> throw new IllegalArgumentException("unsupported room operation: " + command.operation());
            };
            return state.summary();
        }

        private RoomState create(final RoomCommand command) {
            RoomState state = new RoomState(command.roomId(), command.uid());
            state.players().add(command.uid());
            rooms.put(command.roomId(), state);
            return state;
        }

        private RoomState join(final RoomCommand command) {
            RoomState state = requireRoom(command.roomId());
            state.players().add(command.uid());
            return state;
        }

        private RoomState ready(final RoomCommand command) {
            RoomState state = requireRoom(command.roomId());
            if (!state.players().contains(command.uid())) {
                throw new IllegalStateException("player is not in room: " + command.uid());
            }
            state.readyPlayers().add(command.uid());
            return state;
        }

        private RoomState start(final RoomCommand command) {
            RoomState state = requireRoom(command.roomId());
            state.started(state.players().size() > 1 && state.readyPlayers().containsAll(state.players()));
            return state;
        }

        private RoomState frame(final RoomCommand command) {
            RoomState state = requireRoom(command.roomId());
            if (!state.started()) {
                throw new IllegalStateException("room is not started: " + command.roomId());
            }
            state.lastFrame(Math.max(state.lastFrame(), command.frame()));
            state.inputSum(state.inputSum() + command.input());
            return state;
        }

        private RoomState requireRoom(final String roomId) {
            RoomState state = rooms.get(roomId);
            if (state == null) {
                throw new IllegalStateException("room not found: " + roomId);
            }
            return state;
        }
    }

    /**
     * Room state.
     *
     * @author zn
     */
    private static final class RoomState {

        private final String roomId;

        private final long ownerUid;

        private final LinkedHashSet<Long> players = new LinkedHashSet<>();

        private final LinkedHashSet<Long> readyPlayers = new LinkedHashSet<>();

        private boolean started;

        private int lastFrame;

        private int inputSum;

        private RoomState(final String roomId, final long ownerUid) {
            this.roomId = Objects.requireNonNull(roomId, "roomId");
            this.ownerUid = ownerUid;
        }

        private LinkedHashSet<Long> players() {
            return players;
        }

        private LinkedHashSet<Long> readyPlayers() {
            return readyPlayers;
        }

        private boolean started() {
            return started;
        }

        private void started(final boolean started) {
            this.started = started;
        }

        private int lastFrame() {
            return lastFrame;
        }

        private void lastFrame(final int lastFrame) {
            this.lastFrame = lastFrame;
        }

        private int inputSum() {
            return inputSum;
        }

        private void inputSum(final int inputSum) {
            this.inputSum = inputSum;
        }

        private String summary() {
            return "room=" + roomId
                    + ",owner=" + ownerUid
                    + ",players=" + players.size()
                    + ",ready=" + readyPlayers.size()
                    + ",started=" + started
                    + ",frame=" + lastFrame
                    + ",inputSum=" + inputSum;
        }
    }

    /**
     * Room command.
     *
     * @param operation operation name.
     * @param roomId room ID.
     * @param uid player ID.
     * @param frame frame number.
     * @param input input value.
     * @param traceId trace ID.
     */
    private record RoomCommand(String operation, String roomId, long uid, int frame, int input, String traceId) {

        private static RoomCommand createRoom(final String roomId, final long ownerUid, final String traceId) {
            return new RoomCommand("create", roomId, ownerUid, 0, 0, traceId);
        }

        private static RoomCommand joinRoom(final String roomId, final long uid, final String traceId) {
            return new RoomCommand("join", roomId, uid, 0, 0, traceId);
        }

        private static RoomCommand ready(final String roomId, final long uid, final String traceId) {
            return new RoomCommand("ready", roomId, uid, 0, 0, traceId);
        }

        private static RoomCommand startMatch(final String roomId, final String traceId) {
            return new RoomCommand("start", roomId, 0L, 0, 0, traceId);
        }

        private static RoomCommand submitFrame(
                final String roomId,
                final long uid,
                final int frame,
                final int input,
                final String traceId) {
            return new RoomCommand("frame", roomId, uid, frame, input, traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param roomSummary room summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String roomSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "room-game=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + roomSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
