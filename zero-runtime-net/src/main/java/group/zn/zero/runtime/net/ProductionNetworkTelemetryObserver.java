package group.zn.zero.runtime.net;

import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricRegistry;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.ConnectionLifecycleResult;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkRateLimitScope;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * production starter 连接日志与低基数指标 observer。
 *
 * <p>该 observer 不创建线程；调用方必须通过 starter background executor 投递。日志可以包含
 * connectionId 和已脱敏地址，指标严格只使用确认过的六类标签。</p>
 *
 * @author zn
 */
public final class ProductionNetworkTelemetryObserver implements ConnectionLifecycleObserver {

    /** 活跃连接指标。 */
    public static final String CONNECTIONS_ACTIVE = "zero_net_connections_active";
    /** 生命周期事件 counter。 */
    public static final String CONNECTION_EVENTS_TOTAL = "zero_net_connection_events_total";
    /** 连接拒绝 counter。 */
    public static final String CONNECTION_REJECTIONS_TOTAL = "zero_net_connection_rejections_total";
    /** 握手耗时样本。 */
    public static final String HANDSHAKE_LATENCY_SECONDS = "zero_net_handshake_latency_seconds";
    /** 鉴权耗时样本。 */
    public static final String AUTH_LATENCY_SECONDS = "zero_net_auth_latency_seconds";
    /** 心跳超时 counter。 */
    public static final String HEARTBEAT_TIMEOUTS_TOTAL = "zero_net_heartbeat_timeouts_total";
    /** 限流 counter。 */
    public static final String RATE_LIMITED_TOTAL = "zero_net_rate_limited_total";

    /**
     * 允许进入网络指标的低基数标签。
     */
    private static final Set<String> ALLOWED_METRIC_LABELS = Set.of(
            "listener", "protocol", "event", "result", "reason", "scope");

    /** production 网络日志来源。 */
    private static final LogSource LOG_SOURCE = new LogSource("zero-server", "production", "zero-net");
    /** 连接状态低基数标签缓存。 */
    private static final String[] STATE_LABELS = lowercaseLabels(ConnectionLifecycleState.values());
    /** 生命周期事件低基数标签缓存。 */
    private static final String[] EVENT_LABELS = lowercaseLabels(ConnectionLifecycleEventType.values());
    /** 生命周期结果低基数标签缓存。 */
    private static final String[] RESULT_LABELS = lowercaseLabels(ConnectionLifecycleResult.values());
    /** 拒绝原因低基数标签缓存。 */
    private static final String[] REASON_LABELS = lowercaseLabels(ConnectionRejectionReason.values());
    /** 限流范围低基数标签缓存。 */
    private static final String[] SCOPE_LABELS = lowercaseLabels(NetworkRateLimitScope.values());
    /** 生命周期日志操作名缓存。 */
    private static final String[] EVENT_OPERATIONS = prefixedLabels("network-", EVENT_LABELS);
    /** 安全日志写入端口。 */
    private final LogAppender logAppender;
    /** 指标注册表。 */
    private final MetricRegistry metricRegistry;
    /**
     * 按 listener/protocol 隔离的活跃连接数。
     *
     * <p>标签组合来自配置级低基数字段。每个 AtomicLong 同时作为该时序的发布锁，使“更新 + 记录样本”
     * 按时序线性化；计数归零后仍保留槽位，避免并发移除与接入竞争导致丢计数。</p>
     */
    private final ConcurrentMap<ConnectionSeriesKey, AtomicLong> activeConnections = new ConcurrentHashMap<>();

    /**
     * 创建 production 网络遥测 observer 并注册指标定义。
     *
     * @param logAppender 安全日志写入端口；不可为空；失败必须抛出统一异常。
     * @param metricRegistry 指标注册表；不可为空。
     */
    public ProductionNetworkTelemetryObserver(
            final LogAppender logAppender,
            final MetricRegistry metricRegistry) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.metricRegistry = Objects.requireNonNull(metricRegistry, "metricRegistry");
        registerMetrics();
    }

    /**
     * 返回允许的指标标签白名单。
     *
     * @return 不可变、无序、非空、线程安全的标签集合。
     */
    public static Set<String> allowedMetricLabels() {
        return ALLOWED_METRIC_LABELS;
    }

    /**
     * 同步写入单条日志和对应指标样本。
     *
     * <p>该方法线程安全性由注入 sink/registry 保证，不修改游戏业务数据。</p>
     *
     * @param observation 生命周期观测事件；不可为空。
     */
    @Override
    public void onEvent(final ConnectionLifecycleObservation observation) {
        ConnectionLifecycleObservation current = Objects.requireNonNull(observation, "observation");
        appendLog(current);
        recordEvent(current);
        recordSpecificMetrics(current);
    }

    private void registerMetrics() {
        metricRegistry.register(new MetricDefinition(
                CONNECTIONS_ACTIVE,
                "Active production TCP connections",
                "count",
                List.of("listener", "protocol")));
        metricRegistry.register(new MetricDefinition(
                CONNECTION_EVENTS_TOTAL,
                "Production connection events",
                "count",
                List.of("listener", "protocol", "event", "result")));
        metricRegistry.register(new MetricDefinition(
                CONNECTION_REJECTIONS_TOTAL,
                "Rejected production connections",
                "count",
                List.of("listener", "protocol", "reason")));
        metricRegistry.register(new MetricDefinition(
                HANDSHAKE_LATENCY_SECONDS,
                "Production handshake latency",
                "seconds",
                List.of("listener", "protocol", "result")));
        metricRegistry.register(new MetricDefinition(
                AUTH_LATENCY_SECONDS,
                "Production authentication latency",
                "seconds",
                List.of("listener", "protocol", "result")));
        metricRegistry.register(new MetricDefinition(
                HEARTBEAT_TIMEOUTS_TOTAL,
                "Production heartbeat timeouts",
                "count",
                List.of("listener", "protocol")));
        metricRegistry.register(new MetricDefinition(
                RATE_LIMITED_TOTAL,
                "Production network rate limited events",
                "count",
                List.of("listener", "protocol", "scope")));
    }

    private void appendLog(final ConnectionLifecycleObservation observation) {
        Map<String, String> fields = Map.of(
                "connectionId", observation.connectionId(),
                "remoteAddress", observation.remoteAddress(),
                "state", label(observation.state()),
                "event", label(observation.event()),
                "result", label(observation.result()),
                "reason", label(observation.reason()),
                "scope", label(observation.scope()),
                "latencyMillis", Long.toString(observation.latencyNanos() / 1_000_000L),
                "inboundInFlight", Integer.toString(observation.inboundInFlight()));
        LogResult result = logResult(observation.result());
        logAppender.append(ZeroLogRecord.create(
                observation.time(),
                logLevel(result),
                logType(observation),
                LOG_SOURCE,
                new LogOperation(
                        EVENT_OPERATIONS[observation.event().ordinal()],
                        result,
                        result == LogResult.SUCCESS ? null : observation.errorCode()),
                observation.traceId(),
                "production network lifecycle event",
                fields));
    }

    private LogResult logResult(final ConnectionLifecycleResult result) {
        return switch (result) {
            case OBSERVED, SUCCEEDED -> LogResult.SUCCESS;
            case REJECTED -> LogResult.REJECTED;
            case FAILED -> LogResult.FAILURE;
        };
    }

    private LogLevel logLevel(final LogResult result) {
        return switch (result) {
            case FAILURE, TIMEOUT -> LogLevel.ERROR;
            case REJECTED, DEGRADED -> LogLevel.WARN;
            case STARTED, SUCCESS -> LogLevel.INFO;
        };
    }

    private LogType logType(final ConnectionLifecycleObservation observation) {
        if (observation.result() == ConnectionLifecycleResult.REJECTED
                || observation.event() == ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED) {
            return LogType.SECURITY;
        }
        if (observation.result() == ConnectionLifecycleResult.FAILED) {
            return LogType.ERROR;
        }
        if (observation.event() == ConnectionLifecycleEventType.HANDSHAKE_SUCCEEDED
                || observation.event() == ConnectionLifecycleEventType.AUTH_SUCCEEDED) {
            return LogType.PERFORMANCE;
        }
        return LogType.RUNTIME;
    }

    private void recordEvent(final ConnectionLifecycleObservation observation) {
        record(
                CONNECTION_EVENTS_TOTAL,
                1D,
                observation,
                Map.of(
                        "listener", observation.listener(),
                        "protocol", observation.protocol(),
                        "event", label(observation.event()),
                        "result", label(observation.result())));
        if (observation.event() == ConnectionLifecycleEventType.CHANNEL_ACCEPTED) {
            recordActiveConnections(observation, true);
        } else if (observation.event() == ConnectionLifecycleEventType.CHANNEL_CLOSED) {
            recordActiveConnections(observation, false);
        }
    }

    /**
     * 更新并发布单个 listener/protocol 时序的活跃连接 gauge。
     *
     * <p>同一时序的计数更新与样本写入由该时序自己的 monitor 串行化；不同标签时序互不阻塞。
     * 该方法不修改连接业务状态，不创建线程，不执行外部 IO。</p>
     *
     * @param observation 生命周期观测事件；不可为空。
     * @param accepted {@code true} 表示接入并递增，{@code false} 表示关闭并递减且钳制为零。
     */
    private void recordActiveConnections(
            final ConnectionLifecycleObservation observation,
            final boolean accepted) {
        AtomicLong counter = activeConnectionCounter(observation);
        synchronized (counter) {
            long active = accepted
                    ? counter.incrementAndGet()
                    : counter.updateAndGet(value -> Math.max(0L, value - 1L));
            record(CONNECTIONS_ACTIVE, active, observation, baseLabels(observation));
        }
    }

    private AtomicLong activeConnectionCounter(final ConnectionLifecycleObservation observation) {
        ConnectionSeriesKey key = new ConnectionSeriesKey(observation.listener(), observation.protocol());
        return activeConnections.computeIfAbsent(key, ignored -> new AtomicLong());
    }

    private void recordSpecificMetrics(final ConnectionLifecycleObservation observation) {
        if (observation.event() == ConnectionLifecycleEventType.CONNECTION_REJECTED) {
            record(
                    CONNECTION_REJECTIONS_TOTAL,
                    1D,
                    observation,
                    Map.of(
                            "listener", observation.listener(),
                            "protocol", observation.protocol(),
                            "reason", label(observation.reason())));
        }
        if (observation.event() == ConnectionLifecycleEventType.HANDSHAKE_SUCCEEDED) {
            record(
                    HANDSHAKE_LATENCY_SECONDS,
                    observation.latencyNanos() / 1_000_000_000D,
                    observation,
                    Map.of(
                            "listener", observation.listener(),
                            "protocol", observation.protocol(),
                            "result", label(observation.result())));
        }
        if (observation.event() == ConnectionLifecycleEventType.AUTH_SUCCEEDED) {
            record(
                    AUTH_LATENCY_SECONDS,
                    observation.latencyNanos() / 1_000_000_000D,
                    observation,
                    Map.of(
                            "listener", observation.listener(),
                            "protocol", observation.protocol(),
                            "result", label(observation.result())));
        }
        if (observation.event() == ConnectionLifecycleEventType.HEARTBEAT_TIMEOUT) {
            record(HEARTBEAT_TIMEOUTS_TOTAL, 1D, observation, baseLabels(observation));
        }
        if (observation.event() == ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED) {
            record(
                    RATE_LIMITED_TOTAL,
                    1D,
                    observation,
                    Map.of(
                            "listener", observation.listener(),
                            "protocol", observation.protocol(),
                            "scope", label(observation.scope())));
        }
    }

    private void record(
            final String name,
            final double value,
            final ConnectionLifecycleObservation observation,
            final Map<String, String> labels) {
        metricRegistry.record(new MetricSample(name, value, labels, observation.time()));
    }

    private Map<String, String> baseLabels(final ConnectionLifecycleObservation observation) {
        return Map.of("listener", observation.listener(), "protocol", observation.protocol());
    }

    private String label(final ConnectionLifecycleState value) {
        return STATE_LABELS[value.ordinal()];
    }

    private String label(final ConnectionLifecycleEventType value) {
        return EVENT_LABELS[value.ordinal()];
    }

    private String label(final ConnectionLifecycleResult value) {
        return RESULT_LABELS[value.ordinal()];
    }

    private String label(final ConnectionRejectionReason value) {
        return REASON_LABELS[value.ordinal()];
    }

    private String label(final NetworkRateLimitScope value) {
        return SCOPE_LABELS[value.ordinal()];
    }

    private static String[] lowercaseLabels(final Enum<?>[] values) {
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            labels[index] = values[index].name().toLowerCase(Locale.ROOT);
        }
        return labels;
    }

    private static String[] prefixedLabels(final String prefix, final String[] values) {
        String[] labels = new String[values.length];
        for (int index = 0; index < values.length; index++) {
            labels[index] = prefix + values[index];
        }
        return labels;
    }

    /**
     * 活跃连接指标的低基数时序键。
     *
     * @param listener 监听入口名。
     * @param protocol 传输协议名。
     */
    private record ConnectionSeriesKey(String listener, String protocol) {
    }
}
