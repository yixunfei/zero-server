package __PACKAGE__;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.monitor.MetricDefinition;
import group.zn.zero.monitor.MetricSample;
import group.zn.zero.monitor.MonitorRuntime;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;

/** 已接收、已完成和失败分别记录；保留 traceId 与 ErrorCode，不记录原始凭据或异常文本。 @author zn */
public final class LocalGameObservation {
    /** 有界结果计数，不含请求级标签。 */
    private static final String COMMAND_METRIC = "local_game_command_total";
    /** 终端日志端口；必须已接入框架日志安全链。 */
    private final LogAppender log;
    /** 共享运行时指标。 */
    private final MonitorRuntime monitor;
    /** 稳定日志来源。 */
    private final LogSource source;
    /** 完成计数。 */
    private final AtomicInteger completed = new AtomicInteger();
    /** 失败计数。 */
    private final AtomicInteger failed = new AtomicInteger();
    /** 三个固定动作的累计计数，recordMetric 的实例锁保护，避免请求标签膨胀。 */
    private final Map<String, long[]> counts = Map.of(
            "login", new long[LogResult.values().length],
            "enterScene", new long[LogResult.values().length],
            "move", new long[LogResult.values().length]);

    /** 默认本地观察器，错误输出稳定码；用于没有监控装配的测试与示例。 */
    public LocalGameObservation() {
        this(consoleAppender(), null, new LogSource("local-game", "local", "business"));
    }

    /** @return 经过安全门的本地终端 appender；输出 traceId 和稳定错误码，线程安全，无线程分配。 */
    public static LogAppender consoleAppender() {
        return new LogPipeline(List.of(), record -> System.getLogger(LocalGameObservation.class.getName()).log(
                record.logOperation().result() == LogResult.FAILURE ? System.Logger.Level.ERROR : System.Logger.Level.DEBUG,
                "trace=" + record.traceId() + "|" + record));
    }

    /**
     * 创建共享观察器，无线程分配；指标只注册一次，每个 runtime 共用该实例。
     * @param log 安全日志端口。 @param monitor 可选监控。 @param source 稳定来源。
     * @throws NullPointerException log/source 为空。构造后线程安全。
     */
    public LocalGameObservation(final LogAppender log, final MonitorRuntime monitor, final LogSource source) {
        this.log = Objects.requireNonNull(log, "log");
        this.monitor = monitor;
        this.source = Objects.requireNonNull(source, "source");
        if (monitor != null) monitor.registry().register(new MetricDefinition(
                COMMAND_METRIC, "local game command lifecycle", "count", List.of("action", "result")));
    }

    /** @return 稳定指标名称；只读，线程安全。 */
    public static String commandMetric() { return COMMAND_METRIC; }

    /** @param action 稳定动作。 @param traceId 请求关联标识。记录开始，不代表业务完成。 */
    public void accepted(final String action, final String traceId) { record(action, traceId, LogResult.STARTED, null); }

    /**
     * @param action 稳定动作。 @param traceId 请求标识。 @param stage 异步业务阶段。
     * @param <T> 业务结果类型。 @return 保留结果或统一异常的阶段，无阻塞；日志失败向调用者传播。
     */
    public <T> CompletionStage<T> observe(final String action, final String traceId, final CompletionStage<T> stage) {
        return stage.handle((value, failure) -> {
            if (failure == null) {
                completed.incrementAndGet();
                record(action, traceId, LogResult.SUCCESS, null);
                return value;
            }
            ZeroException error = error(failure);
            failed.incrementAndGet();
            record(action, traceId, LogResult.FAILURE, error);
            throw error;
        });
    }

    /** @param failure 原始失败。 @return 带稳定 ErrorCode 且保留 cause 的异常，不改业务状态。 */
    public static ZeroException error(final Throwable failure) {
        Throwable cause = failure;
        while (cause instanceof CompletionException && cause.getCause() != null) cause = cause.getCause();
        return cause instanceof ZeroException zero ? zero : ZeroException.of(
                SystemErrorCode.SYSTEM_ERROR, SystemErrorCode.SYSTEM_ERROR.message(), cause);
    }

    private void record(final String action, final String traceId, final LogResult result, final ZeroException error) {
        log.append(ZeroLogRecord.create(Instant.now(), error == null ? LogLevel.INFO : LogLevel.ERROR,
                error == null ? LogType.BUSINESS : LogType.ERROR, source,
                new LogOperation(action, result, error == null ? null : error.errorCode()), traceId,
                "business command " + result.name(), Map.of("action", action)));
        if (monitor != null) recordMetric(action, result);
    }

    private synchronized void recordMetric(final String action, final LogResult result) {
        long count = ++counts.get(action)[result.ordinal()];
        monitor.registry().record(new MetricSample(COMMAND_METRIC, count,
                Map.of("action", action, "result", result.name().toLowerCase(java.util.Locale.ROOT)), Instant.now()));
    }

    /** @return 完成数；只读，线程安全。 */
    public int completedCount() { return completed.get(); }
    /** @return 失败数；只读，线程安全。 */
    public int failedCount() { return failed.get(); }
}
