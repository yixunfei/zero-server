package __PACKAGE__;

import __PACKAGE__.generated.bo.FrameSyncAdvanceFrameEventBO;
import __PACKAGE__.generated.bo.FrameSyncJoinMatchEventBO;
import __PACKAGE__.generated.bo.FrameSyncQuerySnapshotEventBO;
import __PACKAGE__.generated.bo.FrameSyncSubmitInputEventBO;
import __PACKAGE__.generated.dto.codec.FrameSyncAdvanceFrameProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.FrameSyncJoinMatchProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.FrameSyncQuerySnapshotProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.FrameSyncSubmitInputProtocolDTOCodec;
import __PACKAGE__.generated.dto.FrameSyncAdvanceFrameProtocolDTO;
import __PACKAGE__.generated.dto.FrameSyncJoinMatchProtocolDTO;
import __PACKAGE__.generated.dto.FrameSyncQuerySnapshotProtocolDTO;
import __PACKAGE__.generated.dto.FrameSyncSubmitInputProtocolDTO;
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
import java.util.TreeMap;
import java.util.UUID;

/**
 * Local frame sync scaffold application.
 *
 * <p>This class wires generated frame-sync protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates fixed-frame input collection and snapshot summaries without
 * external middleware. It is not a production frame-sync API.</p>
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
     * Frame command metric.
     */
    private static final String FRAME_COMMAND_METRIC = "local_frame_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local frame sync scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol frame sync flow.
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
        try (FrameSyncActor frameActor = new FrameSyncActor(
                runtime, runtime.require(LogRuntime.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(frameActor);

            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_JOIN_MATCH_PROTOCOL,
                    FrameSyncJoinMatchProtocolDTOCodec.INSTANCE, joinRequest(1001L, "trace-frame-join-1"));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_JOIN_MATCH_PROTOCOL,
                    FrameSyncJoinMatchProtocolDTOCodec.INSTANCE, joinRequest(1002L, "trace-frame-join-2"));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_SUBMIT_INPUT_PROTOCOL,
                    FrameSyncSubmitInputProtocolDTOCodec.INSTANCE, inputRequest(1001L, 1, 7));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_SUBMIT_INPUT_PROTOCOL,
                    FrameSyncSubmitInputProtocolDTOCodec.INSTANCE, inputRequest(1002L, 1, 4));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_ADVANCE_FRAME_PROTOCOL,
                    FrameSyncAdvanceFrameProtocolDTOCodec.INSTANCE, advanceRequest(1));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_SUBMIT_INPUT_PROTOCOL,
                    FrameSyncSubmitInputProtocolDTOCodec.INSTANCE, inputRequest(1001L, 2, 6));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_ADVANCE_FRAME_PROTOCOL,
                    FrameSyncAdvanceFrameProtocolDTOCodec.INSTANCE, advanceRequest(2));
            dispatch(dispatcher, ProtocolIds.FRAME_SYNC_QUERY_SNAPSHOT_PROTOCOL,
                    FrameSyncQuerySnapshotProtocolDTOCodec.INSTANCE, snapshotRequest());

            return new DemoResult(
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    frameActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            runtime.close();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final FrameSyncActor frameActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        FrameSyncBO bo = new FrameSyncBO(frameActor);
        dispatcher.registerFrameSyncJoinMatchEventBO(bo);
        dispatcher.registerFrameSyncSubmitInputEventBO(bo);
        dispatcher.registerFrameSyncAdvanceFrameEventBO(bo);
        dispatcher.registerFrameSyncQuerySnapshotEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                FRAME_COMMAND_METRIC,
                "local frame command count",
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

    private static FrameSyncJoinMatchProtocolDTO joinRequest(final long uid, final String traceId) {
        FrameSyncJoinMatchProtocolDTO request = new FrameSyncJoinMatchProtocolDTO();
        request.uid = uid;
        request.matchId = "match-1";
        request.traceId = traceId;
        return request;
    }

    private static FrameSyncSubmitInputProtocolDTO inputRequest(final long uid, final int frame, final int input) {
        FrameSyncSubmitInputProtocolDTO request = new FrameSyncSubmitInputProtocolDTO();
        request.uid = uid;
        request.matchId = "match-1";
        request.frame = frame;
        request.input = input;
        request.traceId = "trace-frame-input-" + uid + "-" + frame;
        return request;
    }

    private static FrameSyncAdvanceFrameProtocolDTO advanceRequest(final int frame) {
        FrameSyncAdvanceFrameProtocolDTO request = new FrameSyncAdvanceFrameProtocolDTO();
        request.matchId = "match-1";
        request.frame = frame;
        request.traceId = "trace-frame-advance-" + frame;
        return request;
    }

    private static FrameSyncQuerySnapshotProtocolDTO snapshotRequest() {
        FrameSyncQuerySnapshotProtocolDTO request = new FrameSyncQuerySnapshotProtocolDTO();
        request.matchId = "match-1";
        request.traceId = "trace-frame-snapshot";
        return request;
    }

    /**
     * Generated frame-sync business implementation.
     *
     * @author zn
     */
    private static final class FrameSyncBO implements
            FrameSyncJoinMatchEventBO,
            FrameSyncSubmitInputEventBO,
            FrameSyncAdvanceFrameEventBO,
            FrameSyncQuerySnapshotEventBO {

        /**
         * Frame Actor facade.
         */
        private final FrameSyncActor frameActor;

        private FrameSyncBO(final FrameSyncActor frameActor) {
            this.frameActor = Objects.requireNonNull(frameActor, "frameActor");
        }

        @Override
        public void joinMatch(final FrameSyncJoinMatchProtocolDTO request) {
            frameActor.dispatch(FrameCommand.join(request.matchId, request.uid, request.traceId));
        }

        @Override
        public void submitInput(final FrameSyncSubmitInputProtocolDTO request) {
            frameActor.dispatch(FrameCommand.input(
                    request.matchId,
                    request.uid,
                    request.frame,
                    request.input,
                    request.traceId));
        }

        @Override
        public void advanceFrame(final FrameSyncAdvanceFrameProtocolDTO request) {
            frameActor.dispatch(FrameCommand.advance(request.matchId, request.frame, request.traceId));
        }

        @Override
        public void querySnapshot(final FrameSyncQuerySnapshotProtocolDTO request) {
            frameActor.dispatch(FrameCommand.snapshot(request.matchId, request.traceId));
        }
    }

    /**
     * Frame sync Actor facade.
     *
     * @author zn
     */
    private static final class FrameSyncActor implements AutoCloseable {

        private final ActorScheduler scheduler;

        /**
         * Safe log appender.
         */
        private final LogAppender logAppender;

        private final MonitorRuntime monitorRuntime;

        private final MatchStore matchStore = new MatchStore();

        private final AtomicReference<String> summary = new AtomicReference<>("");

        private final ActorSubscription subscription;

        private FrameSyncActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(ActorRuntime.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(FrameCommand.class, ActorHandler.sync((context, message) -> {
                FrameCommand command = (FrameCommand) message.payload();
                String current = matchStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final FrameCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.custom("frame:" + command.matchId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final FrameCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "frame command handled",
                    Map.of("operation", command.operation(), "matchId", command.matchId())));
            monitorRuntime.registry().record(new MetricSample(
                    FRAME_COMMAND_METRIC,
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
     * Local match store.
     *
     * @author zn
     */
    private static final class MatchStore {

        private final Map<String, MatchState> matches = new LinkedHashMap<>();

        private String apply(final FrameCommand command) {
            MatchState state = matches.computeIfAbsent(command.matchId(), MatchState::new);
            return switch (command.operation()) {
                case "join" -> join(state, command);
                case "input" -> input(state, command);
                case "advance" -> advance(state, command);
                case "snapshot" -> state.summary();
                default -> throw new IllegalArgumentException("unsupported frame operation: " + command.operation());
            };
        }

        private String join(final MatchState state, final FrameCommand command) {
            state.players().add(command.uid());
            return state.summary();
        }

        private String input(final MatchState state, final FrameCommand command) {
            if (!state.players().contains(command.uid())) {
                throw new IllegalStateException("player is not in match: " + command.uid());
            }
            state.inputs().computeIfAbsent(command.frame(), ignored -> new LinkedHashMap<>())
                    .put(command.uid(), command.input());
            return state.summary();
        }

        private String advance(final MatchState state, final FrameCommand command) {
            Map<Long, Integer> frameInputs = state.inputs().getOrDefault(command.frame(), Map.of());
            int frameSum = frameInputs.values().stream().mapToInt(Integer::intValue).sum();
            state.currentFrame(command.frame());
            state.inputSum(state.inputSum() + frameSum);
            state.snapshots().put(command.frame(), state.inputSum());
            return state.summary();
        }
    }

    /**
     * Match state.
     *
     * @author zn
     */
    private static final class MatchState {

        private final String matchId;

        private final LinkedHashSet<Long> players = new LinkedHashSet<>();

        private final Map<Integer, Map<Long, Integer>> inputs = new TreeMap<>();

        private final Map<Integer, Integer> snapshots = new TreeMap<>();

        private int currentFrame;

        private int inputSum;

        private MatchState(final String matchId) {
            this.matchId = Objects.requireNonNull(matchId, "matchId");
        }

        private LinkedHashSet<Long> players() {
            return players;
        }

        private Map<Integer, Map<Long, Integer>> inputs() {
            return inputs;
        }

        private Map<Integer, Integer> snapshots() {
            return snapshots;
        }

        private void currentFrame(final int currentFrame) {
            this.currentFrame = currentFrame;
        }

        private int inputSum() {
            return inputSum;
        }

        private void inputSum(final int inputSum) {
            this.inputSum = inputSum;
        }

        private String summary() {
            return "match=" + matchId
                    + ",players=" + players.size()
                    + ",frame=" + currentFrame
                    + ",inputs=" + inputCount()
                    + ",inputSum=" + inputSum
                    + ",snapshots=" + snapshots.size();
        }

        private int inputCount() {
            return inputs.values().stream().mapToInt(Map::size).sum();
        }
    }

    /**
     * Frame command.
     *
     * @param operation operation name.
     * @param matchId match ID.
     * @param uid player ID.
     * @param frame frame number.
     * @param input input value.
     * @param traceId trace ID.
     */
    private record FrameCommand(String operation, String matchId, long uid, int frame, int input, String traceId) {

        private static FrameCommand join(final String matchId, final long uid, final String traceId) {
            return new FrameCommand("join", matchId, uid, 0, 0, traceId);
        }

        private static FrameCommand input(
                final String matchId,
                final long uid,
                final int frame,
                final int input,
                final String traceId) {
            return new FrameCommand("input", matchId, uid, frame, input, traceId);
        }

        private static FrameCommand advance(final String matchId, final int frame, final String traceId) {
            return new FrameCommand("advance", matchId, 0L, frame, 0, traceId);
        }

        private static FrameCommand snapshot(final String matchId, final String traceId) {
            return new FrameCommand("snapshot", matchId, 0L, 0, 0, traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param snapshotSummary snapshot summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String snapshotSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "frame-sync=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + snapshotSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
