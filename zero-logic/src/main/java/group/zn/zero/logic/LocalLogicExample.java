package group.zn.zero.logic;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.InMemoryMetricRegistry;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.protocol.ProtocolDefinition;
import group.zn.zero.protocol.ProtocolDirection;
import group.zn.zero.protocol.registry.InMemoryProtocolRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 本地逻辑示例流程。
 *
 * <pre>
 * runPlayerQuery
 *   -> 注册协议定义
 *   -> 发布客户端协议事件
 *   -> 按协议名称查找协议
 *   -> 按 player lane 派发 Actor 消息
 *   -> 返回可断言的执行步骤
 * </pre>
 *
 * 本示例只依赖核心抽象和本地确定性实现，不创建线程池，不接入具体中间件。
 *
 * @author zn
 */
public final class LocalLogicExample {

    /**
     * 示例协议定义。
     */
    private static final ProtocolDefinition QUERY_PLAYER_PROTOCOL = new ProtocolDefinition(
            62001,
            "ZeroLogicQueryPlayerProtocol",
            ProtocolDirection.CLIENT_TO_SERVER,
            1);

    /**
     * 示例逻辑耗时指标。
     */
    private static final MetricDefinition LOGIC_FLOW_TOTAL = new MetricDefinition(
            "zero_logic_flow_total",
            "本地逻辑示例流程次数",
            "count",
            List.of("module", "operation", "result"));

    /**
     * 返回示例协议定义。
     *
     * @return 协议定义；不可为空；线程安全；调用方只读使用。
     */
    public ProtocolDefinition protocolDefinition() {
        return QUERY_PLAYER_PROTOCOL;
    }

    /**
     * 运行玩家查询逻辑示例。
     *
     * @param playerId 玩家 ID；不可为空。
     * @param eventId 事件 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 逻辑流程结果；不可为空；步骤列表不可变、有序、可能为空、线程安全。
     * @throws NullPointerException 当玩家 ID、事件 ID 或 traceId 为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当事件、协议或 Actor 链路执行失败时抛出，必须绑定 ErrorCode。
     */
    public LogicFlowResult runPlayerQuery(
            final String playerId,
            final String eventId,
            final String traceId) {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(traceId, "traceId");

        LaneKey laneKey = LaneKey.player(playerId);
        InMemoryDeadLetterSink deadLetterSink = new InMemoryDeadLetterSink();
        InMemoryEventBus eventBus = new InMemoryEventBus(deadLetterSink);
        LocalActorScheduler actorScheduler = new LocalActorScheduler();
        InMemoryProtocolRegistry protocolRegistry = new InMemoryProtocolRegistry();
        List<String> steps = new ArrayList<>();

        protocolRegistry.register(QUERY_PLAYER_PROTOCOL);
        actorScheduler.register(PlayerQueryPayload.class, ActorHandler.sync((context, message) -> {
            PlayerQueryPayload payload = (PlayerQueryPayload) message.payload();
            steps.add("actor:" + payload.protocolName() + ":" + context.laneKey().value());
        }));
        eventBus.addInterceptor(event -> {
            steps.add("interceptor:" + event.traceId());
            return true;
        }, 0);
        eventBus.register(EventType.CLIENT_PROTOCOL, event -> {
            ProtocolDefinition registered = protocolRegistry
                    .findByName(QUERY_PLAYER_PROTOCOL.name())
                    .orElseThrow();
            steps.add("event:" + registered.id());
            return actorScheduler.dispatch(new ActorMessage(
                    "logic-message-" + event.eventId(),
                    laneKey,
                    event.traceId(),
                    new PlayerQueryPayload(registered.name(), event.eventId())));
        }, 0);

        eventBus.publish(new BasicZeroEvent(eventId, EventType.CLIENT_PROTOCOL, traceId))
                .toCompletableFuture()
                .join();

        return new LogicFlowResult(QUERY_PLAYER_PROTOCOL.name(), eventId, traceId, laneKey, steps);
    }

    /**
     * 运行带日志与指标观测信号的玩家查询逻辑示例。
     *
     * <p>该方法用于阶段 2A 的本地观测基线验证。TraceId 进入日志记录，
     * 指标标签只使用 module、operation、result 等低基数字段。
     *
     * @param playerId 玩家 ID；不可为空。
     * @param eventId 事件 ID；不可为空。
     * @param traceId 链路追踪 ID；不可为空。
     * @return 带观测信号的逻辑流程结果；不可为空。
     * @throws NullPointerException 当玩家 ID、事件 ID 或 traceId 为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当逻辑、日志或指标链路失败时抛出，必须绑定 ErrorCode。
     */
    public ObservedLogicFlowResult runObservedPlayerQuery(
            final String playerId,
            final String eventId,
            final String traceId) {
        LogicFlowResult result = runPlayerQuery(playerId, eventId, traceId);
        InMemoryLogSink logSink = new InMemoryLogSink();
        LogAppender logAppender = new LogPipeline(
                List.of(record -> record.withField("route", "local-memory")),
                logSink);
        InMemoryMetricRegistry metricRegistry = new InMemoryMetricRegistry();
        metricRegistry.register(LOGIC_FLOW_TOTAL);

        logAppender.append(ZeroLogRecord.create(
                Instant.now(),
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("zero-server", "local", "zero-logic"),
                new LogOperation("player-query", LogResult.SUCCESS, null),
                traceId,
                "player query flow completed",
                Map.of(
                        "eventId", eventId,
                        "protocol", result.protocolName(),
                        "laneKey", result.laneKey().value())));
        metricRegistry.record(new MetricSample(
                LOGIC_FLOW_TOTAL.name(),
                1D,
                Map.of(
                        "module", "zero-logic",
                        "operation", "player-query",
                        "result", "success"),
                Instant.now()));

        return new ObservedLogicFlowResult(
                result,
                logSink.records(),
                metricRegistry.samples(),
                metricRegistry.exportText());
    }

    /**
     * 玩家查询消息体。
     *
     * @param protocolName 协议名称。
     * @param eventId 事件标识。
     * @author zn
     */
    private record PlayerQueryPayload(String protocolName, String eventId) {
    }
}
