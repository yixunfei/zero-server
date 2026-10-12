package group.zn.zero.examples.business;

import group.zn.zero.cache.CacheStatistics;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.EventSubscription;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.examples.business.generated.bo.DailyRewardClaimDailyRewardEventBO;
import group.zn.zero.examples.business.generated.dto.DailyRewardClaimDailyRewardProtocolDTO;
import group.zn.zero.examples.business.generated.dto.codec.DailyRewardClaimDailyRewardProtocolDTOCodec;
import group.zn.zero.examples.business.generated.protocol.ProtocolIds;
import group.zn.zero.examples.business.generated.protocol.dispatch.GeneratedProtocolDispatcher;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.monitor.MetricType;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.starter.LocalRuntime;
import group.zn.zero.starter.ZeroServerApplication;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 每日道具领取业务纵向示例。
 *
 * <p>入口把生成协议、手写业务服务、内存 Repository/缓存、内部事件、日志和指标装配在一起，
 * 业务处理器本身不感知具体存储实现。</p>
 *
 * @author zn
 */
public final class BusinessSliceApplication {

    /** 示例 runtime 名称。 */
    private static final String MODULE = "business-slice";
    /** 业务事件成功指标。 */
    private static final String CLAIM_METRIC = "zero_example_daily_reward_claim_total";
    /** 固定日志来源。 */
    private static final LogSource LOG_SOURCE = new LogSource(MODULE, "local", MODULE);

    private BusinessSliceApplication() {
    }

    /**
     * 启动示例并输出稳定摘要。
     * 在普通命令行线程同步等待本地依赖；调用间状态独立，不修改外部数据。
     *
     * @param args 未使用的命令行参数；可为空。
     * @throws RuntimeException 装配、协议处理或关闭资源失败；保留失败原因。
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summaryLine());
    }

    /**
     * 执行一次协议驱动的每日领取流程。
     *
     * <p>同一请求发送两次，第二次使用相同幂等键；预期只产生一次库存变化和一次业务事件。</p>
     *
     * @return 示例结果；不可为空；线程安全。
     * @throws RuntimeException 装配、业务流程或资源关闭失败；仅命令行线程同步等待。
     */
    public static DemoResult runDemo() {
        InMemoryLogSink logSink = new InMemoryLogSink();
        MonitorRuntime monitorRuntime = MonitorRuntime.createDefault();
        GameRuntime runtime = LocalRuntime.builder(
                new MapZeroConfig(Map.of(
                        ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                        ZeroRuntimeConfigKeys.ZERO_NAME, MODULE)),
                logSink,
                ZeroRuntimeExecutors.localPrototype("zero-example-business-slice", 2))
                .replace(MonitorRuntimeComponent.MONITOR_RUNTIME, monitorRuntime)
                .build();
        ZeroServerApplication application = new ZeroServerApplication(runtime);
        application.start();
        try {
            registerMetrics(monitorRuntime);
            InMemoryCrudRepository<Long, DailyRewardInventory> inventories = new InMemoryCrudRepository<>();
            InMemoryCacheService<Long, DailyRewardInventory> inventoryCache = new InMemoryCacheService<>();
            InMemoryDeadLetterSink deadLetters = new InMemoryDeadLetterSink();
            InMemoryEventBus eventBus = new InMemoryEventBus(deadLetters, Clock.systemUTC(), Runnable::run);
            AtomicInteger eventCount = new AtomicInteger();
            try (EventSubscription ignored = registerEventHandler(runtime, eventBus, eventCount)) {
                DailyRewardService service = new DailyRewardService(
                        inventories, inventoryCache, eventBus,
                        Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), java.time.ZoneOffset.UTC));
                FlowResults results = new FlowResults();
                GeneratedProtocolDispatcher dispatcher = registerBusinessHandler(service, results);
                dispatch(dispatcher, request("claim-1001-2026-10-12", "trace-claim-first"));
                dispatch(dispatcher, request("claim-1001-2026-10-12", "trace-claim-replay"));
                DailyRewardInventory inventory = service.loadInventory(1001L).toCompletableFuture().join();
                service.loadInventory(1001L).toCompletableFuture().join();
                CacheStatistics cacheStatistics = inventoryCache.statistics();
                return new DemoResult(
                        results.statuses(),
                        inventory.quantity(),
                        eventCount.get(),
                        logSink.records().size(),
                        monitorRuntime.registry().samples().size(),
                        cacheStatistics.hitCount(),
                        deadLetters.deadLetters().size());
            }
        } finally {
            application.stop();
        }
    }

    private static EventSubscription registerEventHandler(
            final GameRuntime runtime,
            final InMemoryEventBus eventBus,
            final AtomicInteger eventCount) {
        return eventBus.register(EventType.INTERNAL, event -> {
            DailyRewardClaimedEvent claimed = (DailyRewardClaimedEvent) event;
            eventCount.incrementAndGet();
            runtime.require(LogRuntime.LOG_APPENDER).append(ZeroLogRecord.create(
                    Instant.now(),
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    LOG_SOURCE,
                    new LogOperation("daily-reward-claimed", LogResult.SUCCESS, null),
                    claimed.traceId(),
                    "daily reward claimed",
                    Map.of("action", "daily-reward-claimed", "itemId", claimed.itemId())));
            runtime.require(MonitorRuntimeComponent.MONITOR_RUNTIME).registry().record(new MetricSample(
                    CLAIM_METRIC,
                    1D,
                    Map.of("action", "accepted"),
                    Instant.now()));
            return java.util.concurrent.CompletableFuture.completedFuture(null);
        });
    }

    private static GeneratedProtocolDispatcher registerBusinessHandler(
            final DailyRewardService service,
            final FlowResults results) {
        GeneratedProtocolDispatcher dispatcher = new GeneratedProtocolDispatcher();
        dispatcher.registerDailyRewardClaimDailyRewardEventBO(
                new DailyRewardClaimDailyRewardEventBO() {
                    @Override
                    public void claimDailyReward(final DailyRewardClaimDailyRewardProtocolDTO request) {
                        // 仅命令行等待本地完成链；网络入口须认证 uid 并异步回到玩家执行域。
                        DailyRewardClaimResult result = service.claim(
                                request.uid,
                                request.idempotencyKey,
                                request.itemId,
                                request.quantity,
                                request.traceId).toCompletableFuture().join();
                        results.add(result);
                    }
                });
        return dispatcher;
    }

    private static DailyRewardClaimDailyRewardProtocolDTO request(
            final String idempotencyKey,
            final String traceId) {
        DailyRewardClaimDailyRewardProtocolDTO request = new DailyRewardClaimDailyRewardProtocolDTO();
        request.uid = 1001L;
        request.idempotencyKey = idempotencyKey;
        request.itemId = "coin";
        request.quantity = 100;
        request.traceId = traceId;
        return request;
    }

    private static void dispatch(
            final GeneratedProtocolDispatcher dispatcher,
            final DailyRewardClaimDailyRewardProtocolDTO request) {
        try (ZeroWriter writer = new ZeroWriter()) {
            DailyRewardClaimDailyRewardProtocolDTOCodec.INSTANCE.write(writer, request);
            if (!dispatcher.dispatch(ProtocolIds.DAILY_REWARD_CLAIM_DAILY_REWARD_PROTOCOL, writer.toByteArray())) {
                throw new IllegalStateException("daily reward protocol handler not found");
            }
        }
    }

    private static void registerMetrics(final MonitorRuntime monitorRuntime) {
        monitorRuntime.registry().register(new MetricDefinition(
                CLAIM_METRIC,
                "Daily reward claims accepted by the business slice",
                "count",
                List.of("action"),
                MetricType.COUNTER));
    }

    /**
     * 业务流程结果。
     *
     * @param statuses 两次请求的状态，按调用顺序排列；防御性复制为不可变列表，可为空，线程安全。
     * @param inventoryQuantity 最终库存数量。
     * @param eventCount 成功领取事件数量。
     * @param logCount 终端日志数量。
     * @param metricCount 指标样本数量。
     * @param cacheHitCount 缓存命中次数。
     * @param deadLetterCount 死信数量。
     * @author zn
     */
    public record DemoResult(
            List<DailyRewardClaimResult.Status> statuses,
            int inventoryQuantity,
            int eventCount,
            int logCount,
            int metricCount,
            long cacheHitCount,
            int deadLetterCount) {

        /** 保存不可变有序快照，防止调用者修改已返回结果；空列表合法。 */
        public DemoResult {
            statuses = List.copyOf(statuses);
        }

        /**
         * 返回稳定摘要行。
         *
         * @return 摘要文本；不可为空；无数据变更；线程安全。
         */
        public String summaryLine() {
            return "business-slice=ok"
                    + "|statuses=" + statuses.stream().map(Enum::name).toList()
                    + "|inventory=" + inventoryQuantity
                    + "|events=" + eventCount
                    + "|logs=" + logCount
                    + "|metrics=" + metricCount
                    + "|cacheHits=" + cacheHitCount
                    + "|deadLetters=" + deadLetterCount;
        }
    }

    /**
     * 业务处理器的最小结果收集器。
     *
     * @author zn
     */
    private static final class FlowResults {
        /** 领取状态列表。 */
        private final List<DailyRewardClaimResult.Status> statuses = new ArrayList<>();

        /**
         * 记录一次结果。
         *
         * @param result 领取结果；不可为空。
         */
        private void add(final DailyRewardClaimResult result) {
            statuses.add(Objects.requireNonNull(result, "result").status());
        }

        /**
         * 返回不可变状态快照。
         *
         * @return 有序、不可变状态列表；线程安全。
         */
        private List<DailyRewardClaimResult.Status> statuses() {
            return List.copyOf(statuses);
        }
    }
}
