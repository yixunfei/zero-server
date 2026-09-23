package __PACKAGE__;

import __PACKAGE__.generated.bo.RankingSeasonQueryPlayerRankEventBO;
import __PACKAGE__.generated.bo.RankingSeasonQueryTopEventBO;
import __PACKAGE__.generated.bo.RankingSeasonResetSeasonEventBO;
import __PACKAGE__.generated.bo.RankingSeasonSubmitScoreEventBO;
import __PACKAGE__.generated.dto.codec.RankingSeasonQueryPlayerRankProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RankingSeasonQueryTopProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RankingSeasonResetSeasonProtocolDTOCodec;
import __PACKAGE__.generated.dto.codec.RankingSeasonSubmitScoreProtocolDTOCodec;
import __PACKAGE__.generated.dto.RankingSeasonQueryPlayerRankProtocolDTO;
import __PACKAGE__.generated.dto.RankingSeasonQueryTopProtocolDTO;
import __PACKAGE__.generated.dto.RankingSeasonResetSeasonProtocolDTO;
import __PACKAGE__.generated.dto.RankingSeasonSubmitScoreProtocolDTO;
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
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.atomic.AtomicReference;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.UUID;

/**
 * Local ranking season scaffold application.
 *
 * <p>This class wires generated ranking season protocol DTO, codec, BO and dispatcher to a local
 * Actor lane. It demonstrates score submit, ranking query and season reset without external
 * middleware. It is not a production RankingService or SeasonService API.</p>
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
     * Ranking command metric.
     */
    private static final String RANKING_COMMAND_METRIC = "local_ranking_command_total";

    private __APP_CLASS__() {
    }

    /**
     * Starts the local ranking season scaffold.
     *
     * @param args command line arguments; currently unused; may be empty.
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * Runs the local generated-protocol ranking season flow.
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
        try (RankingActor rankingActor = new RankingActor(
                runtime, runtime.require(LogRuntime.LOG_APPENDER), monitorRuntime)) {
            registerMetrics(monitorRuntime);
            GeneratedProtocolDispatcher dispatcher = registerHandlers(rankingActor);

            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_SUBMIT_SCORE_PROTOCOL,
                    RankingSeasonSubmitScoreProtocolDTOCodec.INSTANCE, submitRequest(1001L, "season-1", 120));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_SUBMIT_SCORE_PROTOCOL,
                    RankingSeasonSubmitScoreProtocolDTOCodec.INSTANCE, submitRequest(1002L, "season-1", 240));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_SUBMIT_SCORE_PROTOCOL,
                    RankingSeasonSubmitScoreProtocolDTOCodec.INSTANCE, submitRequest(1003L, "season-1", 180));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_QUERY_TOP_PROTOCOL,
                    RankingSeasonQueryTopProtocolDTOCodec.INSTANCE, queryTopRequest("season-1", 2));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_QUERY_PLAYER_RANK_PROTOCOL,
                    RankingSeasonQueryPlayerRankProtocolDTOCodec.INSTANCE, queryRankRequest(1001L, "season-1"));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_RESET_SEASON_PROTOCOL,
                    RankingSeasonResetSeasonProtocolDTOCodec.INSTANCE, resetRequest());
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_SUBMIT_SCORE_PROTOCOL,
                    RankingSeasonSubmitScoreProtocolDTOCodec.INSTANCE, submitRequest(1001L, "season-2", 300));
            dispatch(dispatcher, ProtocolIds.RANKING_SEASON_QUERY_PLAYER_RANK_PROTOCOL,
                    RankingSeasonQueryPlayerRankProtocolDTOCodec.INSTANCE, queryRankRequest(1001L, "season-2"));

            return new DemoResult(
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_MODE, "unknown"),
                    runtime.require(RuntimeBasics.CONFIG).getOrDefault(ZeroRuntimeConfigKeys.ZERO_NAME, "unknown"),
                    rankingActor.summary(),
                    terminalLogSink.records().size(),
                    monitorRuntime.registry().samples().size(),
                    ProtocolIds.MAX_ID);
        } finally {
            runtime.close();
        }
    }

    private static GeneratedProtocolDispatcher registerHandlers(final RankingActor rankingActor) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        RankingBO bo = new RankingBO(rankingActor);
        dispatcher.registerRankingSeasonSubmitScoreEventBO(bo);
        dispatcher.registerRankingSeasonQueryTopEventBO(bo);
        dispatcher.registerRankingSeasonQueryPlayerRankEventBO(bo);
        dispatcher.registerRankingSeasonResetSeasonEventBO(bo);
        return dispatcher;
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                RANKING_COMMAND_METRIC,
                "local ranking command count",
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

    private static RankingSeasonSubmitScoreProtocolDTO submitRequest(
            final long uid,
            final String seasonId,
            final int score) {
        RankingSeasonSubmitScoreProtocolDTO request = new RankingSeasonSubmitScoreProtocolDTO();
        request.uid = uid;
        request.seasonId = seasonId;
        request.score = score;
        request.traceId = "trace-ranking-submit-" + uid + "-" + seasonId;
        return request;
    }

    private static RankingSeasonQueryTopProtocolDTO queryTopRequest(final String seasonId, final int limit) {
        RankingSeasonQueryTopProtocolDTO request = new RankingSeasonQueryTopProtocolDTO();
        request.seasonId = seasonId;
        request.limit = limit;
        request.traceId = "trace-ranking-top-" + seasonId;
        return request;
    }

    private static RankingSeasonQueryPlayerRankProtocolDTO queryRankRequest(final long uid, final String seasonId) {
        RankingSeasonQueryPlayerRankProtocolDTO request = new RankingSeasonQueryPlayerRankProtocolDTO();
        request.uid = uid;
        request.seasonId = seasonId;
        request.traceId = "trace-ranking-rank-" + uid + "-" + seasonId;
        return request;
    }

    private static RankingSeasonResetSeasonProtocolDTO resetRequest() {
        RankingSeasonResetSeasonProtocolDTO request = new RankingSeasonResetSeasonProtocolDTO();
        request.seasonId = "season-1";
        request.nextSeasonId = "season-2";
        request.traceId = "trace-ranking-reset";
        return request;
    }

    /**
     * Generated ranking business implementation.
     *
     * @author zn
     */
    private static final class RankingBO implements
            RankingSeasonSubmitScoreEventBO,
            RankingSeasonQueryTopEventBO,
            RankingSeasonQueryPlayerRankEventBO,
            RankingSeasonResetSeasonEventBO {

        /**
         * Ranking Actor facade.
         */
        private final RankingActor rankingActor;

        private RankingBO(final RankingActor rankingActor) {
            this.rankingActor = Objects.requireNonNull(rankingActor, "rankingActor");
        }

        @Override
        public void submitScore(final RankingSeasonSubmitScoreProtocolDTO request) {
            rankingActor.dispatch(RankingCommand.submit(
                    request.seasonId,
                    request.uid,
                    request.score,
                    request.traceId));
        }

        @Override
        public void queryTop(final RankingSeasonQueryTopProtocolDTO request) {
            rankingActor.dispatch(RankingCommand.top(request.seasonId, request.limit, request.traceId));
        }

        @Override
        public void queryPlayerRank(final RankingSeasonQueryPlayerRankProtocolDTO request) {
            rankingActor.dispatch(RankingCommand.rank(request.seasonId, request.uid, request.traceId));
        }

        @Override
        public void resetSeason(final RankingSeasonResetSeasonProtocolDTO request) {
            rankingActor.dispatch(RankingCommand.reset(request.seasonId, request.nextSeasonId, request.traceId));
        }
    }

    /**
     * Ranking Actor facade.
     *
     * @author zn
     */
    private static final class RankingActor implements AutoCloseable {

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
         * Ranking store.
         */
        private final RankingStore rankingStore = new RankingStore();

        /**
         * Last summary.
         */
        private final AtomicReference<String> summary = new AtomicReference<>("");

        /**
         * Handler subscription.
         */
        private final ActorSubscription subscription;

        private RankingActor(
                final GameRuntime runtime,
                final LogAppender logAppender,
                final MonitorRuntime monitorRuntime) {
            this.scheduler = Objects.requireNonNull(runtime, "runtime")
                    .require(ActorRuntime.ACTOR_SCHEDULER);
            this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
            this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
            this.subscription = scheduler.register(RankingCommand.class, ActorHandler.sync((context, message) -> {
                RankingCommand command = (RankingCommand) message.payload();
                String current = rankingStore.apply(command);
                summary.set(current);
                record(command);
            }));
        }

        private void dispatch(final RankingCommand command) {
            scheduler.dispatch(new ActorMessage(
                    UUID.randomUUID().toString(),
                    LaneKey.custom("ranking:" + command.seasonId()),
                    command.traceId(),
                    command)).toCompletableFuture().join();
        }

        private String summary() {
            return summary.get();
        }

        private void record(final RankingCommand command) {
            logAppender.append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation(command.operation(), LogResult.SUCCESS, null),
                    command.traceId(),
                    "ranking command handled",
                    Map.of("operation", command.operation(), "seasonId", command.seasonId())));
            monitorRuntime.registry().record(new MetricSample(
                    RANKING_COMMAND_METRIC,
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
     * Local ranking store.
     *
     * @author zn
     */
    private static final class RankingStore {

        /**
         * Seasons by ID.
         */
        private final Map<String, SeasonState> seasons = new LinkedHashMap<>();

        /**
         * Reset count.
         */
        private int resets;

        private String apply(final RankingCommand command) {
            return switch (command.operation()) {
                case "submit" -> submit(command);
                case "top" -> top(command);
                case "rank" -> rank(command);
                case "reset" -> reset(command);
                default -> throw new IllegalArgumentException("unsupported ranking operation: "
                        + command.operation());
            };
        }

        private String submit(final RankingCommand command) {
            SeasonState season = season(command.seasonId());
            season.scores().put(command.uid(), command.score());
            season.top(season.topEntries(Math.max(1, season.scores().size())));
            season.lastAction("submit:" + command.uid() + "=" + command.score());
            return season.summary(resets);
        }

        private String top(final RankingCommand command) {
            SeasonState season = season(command.seasonId());
            season.top(season.topEntries(command.limit()));
            season.lastAction("top:" + command.limit());
            return season.summary(resets);
        }

        private String rank(final RankingCommand command) {
            SeasonState season = season(command.seasonId());
            season.player(command.uid());
            season.playerScore(season.scoreOf(command.uid()));
            season.playerRank(season.rankOf(command.uid()));
            season.lastAction("rank:" + command.uid() + "=" + season.playerRank());
            return season.summary(resets);
        }

        private String reset(final RankingCommand command) {
            SeasonState current = season(command.seasonId());
            current.closed(true);
            current.lastAction("reset:" + command.seasonId() + "->" + command.nextSeasonId());
            resets++;
            SeasonState next = season(command.nextSeasonId());
            next.lastAction("reset-from:" + command.seasonId());
            return next.summary(resets);
        }

        private SeasonState season(final String seasonId) {
            return seasons.computeIfAbsent(seasonId, SeasonState::new);
        }
    }

    /**
     * Season state.
     *
     * @author zn
     */
    private static final class SeasonState {

        /**
         * Season ID.
         */
        private final String seasonId;

        /**
         * Scores by player.
         */
        private final Map<Long, Integer> scores = new LinkedHashMap<>();

        /**
         * Last top entries.
         */
        private String top = "";

        /**
         * Last queried player.
         */
        private long player;

        /**
         * Last queried rank.
         */
        private int playerRank;

        /**
         * Last queried score.
         */
        private int playerScore;

        /**
         * Whether season is closed.
         */
        private boolean closed;

        /**
         * Last action.
         */
        private String lastAction = "";

        private SeasonState(final String seasonId) {
            this.seasonId = Objects.requireNonNull(seasonId, "seasonId");
        }

        private Map<Long, Integer> scores() {
            return scores;
        }

        private int scoreOf(final long uid) {
            Integer score = scores.get(uid);
            if (score == null) {
                throw new IllegalStateException("score not found: " + uid);
            }
            return score;
        }

        private int rankOf(final long uid) {
            List<Map.Entry<Long, Integer>> entries = orderedEntries();
            for (int index = 0; index < entries.size(); index++) {
                if (entries.get(index).getKey().longValue() == uid) {
                    return index + 1;
                }
            }
            throw new IllegalStateException("rank not found: " + uid);
        }

        private String topEntries(final int limit) {
            return orderedEntries().stream()
                    .limit(Math.max(0, limit))
                    .map(entry -> entry.getKey() + ":" + entry.getValue())
                    .collect(Collectors.joining(","));
        }

        private List<Map.Entry<Long, Integer>> orderedEntries() {
            List<Map.Entry<Long, Integer>> entries = new ArrayList<>(scores.entrySet());
            entries.sort(Comparator
                    .<Map.Entry<Long, Integer>>comparingInt(Map.Entry::getValue)
                    .reversed()
                    .thenComparingLong(Map.Entry::getKey));
            return entries;
        }

        private int playerRank() {
            return playerRank;
        }

        private void top(final String top) {
            this.top = Objects.requireNonNull(top, "top");
        }

        private void player(final long player) {
            this.player = player;
        }

        private void playerRank(final int playerRank) {
            this.playerRank = playerRank;
        }

        private void playerScore(final int playerScore) {
            this.playerScore = playerScore;
        }

        private void closed(final boolean closed) {
            this.closed = closed;
        }

        private void lastAction(final String lastAction) {
            this.lastAction = Objects.requireNonNull(lastAction, "lastAction");
        }

        private String summary(final int resets) {
            return "season=" + seasonId
                    + ",players=" + scores.size()
                    + ",top=" + top
                    + ",player=" + player
                    + ",rank=" + playerRank
                    + ",score=" + playerScore
                    + ",resets=" + resets
                    + ",lastAction=" + lastAction;
        }
    }

    /**
     * Ranking command.
     *
     * @param operation operation name.
     * @param seasonId season ID.
     * @param nextSeasonId next season ID.
     * @param uid player ID.
     * @param score score value.
     * @param limit query limit.
     * @param traceId trace ID.
     */
    private record RankingCommand(
            String operation,
            String seasonId,
            String nextSeasonId,
            long uid,
            int score,
            int limit,
            String traceId) {

        private static RankingCommand submit(
                final String seasonId,
                final long uid,
                final int score,
                final String traceId) {
            return new RankingCommand("submit", seasonId, "", uid, score, 0, traceId);
        }

        private static RankingCommand top(final String seasonId, final int limit, final String traceId) {
            return new RankingCommand("top", seasonId, "", 0L, 0, limit, traceId);
        }

        private static RankingCommand rank(final String seasonId, final long uid, final String traceId) {
            return new RankingCommand("rank", seasonId, "", uid, 0, 0, traceId);
        }

        private static RankingCommand reset(
                final String seasonId,
                final String nextSeasonId,
                final String traceId) {
            return new RankingCommand("reset", seasonId, nextSeasonId, 0L, 0, 0, traceId);
        }
    }

    /**
     * Demo result.
     *
     * @param mode runtime mode.
     * @param name runtime name.
     * @param rankingSummary ranking summary.
     * @param logCount log count.
     * @param metricCount metric sample count.
     * @param maxProtocolId max protocol ID.
     * @author zn
     */
    public record DemoResult(
            String mode,
            String name,
            String rankingSummary,
            int logCount,
            int metricCount,
            int maxProtocolId) {

        /**
         * Returns one-line summary.
         *
         * @return summary; never null; no data mutation; thread-safe.
         */
        public String summaryLine() {
            return "ranking-season=ok"
                    + "|mode=" + mode
                    + "|name=" + name
                    + "|summary=" + rankingSummary
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|maxProtocolId=" + maxProtocolId;
        }
    }
}
