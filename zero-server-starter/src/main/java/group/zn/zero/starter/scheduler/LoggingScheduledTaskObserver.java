package group.zn.zero.starter.scheduler;

import group.zn.zero.core.scheduler.ScheduledTaskEvent;
import group.zn.zero.core.scheduler.ScheduledTaskEventType;
import group.zn.zero.core.scheduler.ScheduledTaskObserver;
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
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把受管定时任务事件写入标准日志与低基数指标的 Starter observer。
 *
 * <p>日志可包含稳定 taskName 和本次执行标识，但不会访问用户任务对象。指标标签仅包含固定枚举
 * 形成的 scheduleType、result 和 reason，不包含 taskName、taskId、executionId、traceId 或业务实体
 * 标识。实例可被多个后台线程并发调用；日志与指标端口的线程安全性仍由具体实现声明。</p>
 *
 * @author zn
 */
public final class LoggingScheduledTaskObserver implements ScheduledTaskObserver {

    /**
     * 日志模块名。
     */
    private static final String MODULE = "zero-scheduler";

    /**
     * 受管调度器日志来源。
     */
    private static final LogSource LOG_SOURCE = new LogSource("zero-server", "runtime", MODULE);

    /**
     * 执行结果计数指标。
     */
    public static final String EXECUTION_TOTAL = "zero_scheduler_execution_total";

    /**
     * 执行耗时指标。
     */
    public static final String EXECUTION_DURATION_MILLIS = "zero_scheduler_execution_duration_ms";

    /**
     * 聚合跳过次数指标。
     */
    public static final String SKIPPED_TOTAL = "zero_scheduler_skipped_total";

    /**
     * 一次性任务拒绝次数指标。
     */
    public static final String REJECTED_TOTAL = "zero_scheduler_rejected_total";

    /**
     * 本最小切片注册的指标定义。
     */
    private static final List<MetricDefinition> DEFINITIONS = List.of(
            new MetricDefinition(
                    EXECUTION_TOTAL,
                    "Managed scheduler completed executions",
                    "count",
                    List.of("scheduleType", "result")),
            new MetricDefinition(
                    EXECUTION_DURATION_MILLIS,
                    "Managed scheduler execution duration",
                    "milliseconds",
                    List.of("scheduleType", "result")),
            new MetricDefinition(
                    SKIPPED_TOTAL,
                    "Managed scheduler skipped occurrences",
                    "count",
                    List.of("scheduleType", "reason")),
            new MetricDefinition(
                    REJECTED_TOTAL,
                    "Managed scheduler rejected tasks",
                    "count",
                    List.of("scheduleType", "reason")));

    /**
     * 经过框架安全边界的日志写入端口。
     */
    private final LogAppender logAppender;

    /**
     * 指标注册表。
     */
    private final MetricRegistry metricRegistry;

    /**
     * 创建日志与指标 observer，并幂等注册最小切片指标定义。
     *
     * <p>构造过程会修改指标注册表的定义集合，但不会写日志、不创建线程、不执行用户代码。</p>
     *
     * @param logAppender 安全日志写入端口；不可为空。
     * @param metricRegistry 指标注册表；不可为空。
     * @throws NullPointerException 当任一端口为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当指标定义注册失败时抛出。
     */
    public LoggingScheduledTaskObserver(
            final LogAppender logAppender,
            final MetricRegistry metricRegistry) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.metricRegistry = Objects.requireNonNull(metricRegistry, "metricRegistry");
        registerDefinitions();
    }

    /**
     * 写入一条结构化日志，并为完成、跳过或拒绝事件记录指标样本。
     *
     * <p>该方法不修改任务状态。日志写入失败时仍会尝试记录指标，指标失败会作为 suppressed 异常
     * 合并；最终异常由 scheduler observer 隔离层绑定 OBSERVER_FAILED 处理。</p>
     *
     * @param event 调度事件；不可为空。
     * @throws NullPointerException 当事件为空时抛出。
     * @throws RuntimeException 当日志或指标端口拒绝事件时抛出；不会静默吞掉异常。
     */
    @Override
    public void onEvent(final ScheduledTaskEvent event) {
        ScheduledTaskEvent checkedEvent = Objects.requireNonNull(event, "event");
        RuntimeException failure = null;
        try {
            logAppender.append(toLogRecord(checkedEvent));
        } catch (RuntimeException ex) {
            failure = ex;
        }
        try {
            recordMetric(checkedEvent);
        } catch (RuntimeException ex) {
            if (failure == null) {
                failure = ex;
            } else {
                failure.addSuppressed(ex);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void registerDefinitions() {
        for (MetricDefinition definition : DEFINITIONS) {
            metricRegistry.register(definition);
        }
    }

    private ZeroLogRecord toLogRecord(final ScheduledTaskEvent event) {
        String result = normalized(event.eventType());
        LogResult logResult = logResult(event.eventType());
        return ZeroLogRecord.create(
                event.observedAt(),
                logLevel(logResult),
                isError(event.eventType()) ? LogType.ERROR : LogType.RUNTIME,
                LOG_SOURCE,
                new LogOperation(
                        "managed-scheduler-" + result,
                        logResult,
                        requiresErrorCode(logResult) ? event.errorCode() : null),
                event.traceId(),
                "managed scheduler event " + result,
                logFields(event, result));
    }

    private Map<String, String> logFields(final ScheduledTaskEvent event, final String result) {
        LinkedHashMap<String, String> fields = new LinkedHashMap<>();
        fields.put("taskName", event.taskName());
        fields.put("taskId", event.taskId());
        fields.put("scheduleType", normalized(event.scheduleType()));
        fields.put("result", result);
        fields.put("occurrenceCount", Long.toString(event.occurrenceCount()));
        fields.put("runSequence", Long.toString(event.runSequence()));
        fields.put("durationMillis", Long.toString(event.elapsed().toMillis()));
        fields.put("reason", event.reason());
        if (event.hasExecution()) {
            fields.put("executionId", event.executionId());
            group.zn.zero.core.scheduler.ScheduledTaskExecutionTiming timing =
                    event.executionTiming().orElseThrow();
            fields.put("scheduledAt", timing.scheduledAt().toString());
            fields.put("startedAt", timing.startedAt().toString());
            fields.put("delayMillis", Long.toString(timing.startDelay().toMillis()));
            if (event.eventType() == ScheduledTaskEventType.SUCCEEDED
                    || event.eventType() == ScheduledTaskEventType.FAILED) {
                fields.put("completedAt", event.observedAt().toString());
            }
        }
        return Map.copyOf(fields);
    }

    private void recordMetric(final ScheduledTaskEvent event) {
        switch (event.eventType()) {
            case SUCCEEDED, FAILED -> recordExecutionMetrics(event);
            case SKIPPED_RUNNING, SKIPPED_CAPACITY, SKIPPED_LATE -> record(
                    SKIPPED_TOTAL,
                    event.occurrenceCount(),
                    labels(event, event.reason()),
                    event.observedAt());
            case REJECTED -> record(
                    REJECTED_TOTAL,
                    event.occurrenceCount(),
                    labels(event, event.reason()),
                    event.observedAt());
            case REGISTERED, STARTED, CANCELLED, STOP_TIMEOUT -> {
                // 本最小切片不为控制事件创建额外指标，日志仍完整保留事件。
            }
        }
    }

    private void recordExecutionMetrics(final ScheduledTaskEvent event) {
        String result = normalized(event.eventType());
        Map<String, String> labels = Map.of(
                "scheduleType", normalized(event.scheduleType()),
                "result", result);
        record(EXECUTION_TOTAL, event.occurrenceCount(), labels, event.observedAt());
        record(EXECUTION_DURATION_MILLIS, event.elapsed().toNanos() / 1_000_000D, labels, event.observedAt());
    }

    private Map<String, String> labels(final ScheduledTaskEvent event, final String reason) {
        return Map.of(
                "scheduleType", normalized(event.scheduleType()),
                "reason", reason);
    }

    private void record(
            final String name,
            final double value,
            final Map<String, String> labels,
            final Instant observedAt) {
        metricRegistry.record(new MetricSample(name, value, labels, observedAt));
    }

    private boolean isError(final ScheduledTaskEventType eventType) {
        return eventType == ScheduledTaskEventType.FAILED
                || eventType == ScheduledTaskEventType.REJECTED
                || eventType == ScheduledTaskEventType.STOP_TIMEOUT;
    }

    private LogResult logResult(final ScheduledTaskEventType eventType) {
        return switch (eventType) {
            case STARTED -> LogResult.STARTED;
            case REGISTERED, SUCCEEDED, CANCELLED -> LogResult.SUCCESS;
            case FAILED -> LogResult.FAILURE;
            case SKIPPED_RUNNING, SKIPPED_CAPACITY, SKIPPED_LATE -> LogResult.SUCCESS;
            case REJECTED -> LogResult.REJECTED;
            case STOP_TIMEOUT -> LogResult.TIMEOUT;
        };
    }

    private boolean requiresErrorCode(final LogResult result) {
        return result != LogResult.STARTED && result != LogResult.SUCCESS;
    }

    private LogLevel logLevel(final LogResult result) {
        return switch (result) {
            case FAILURE, TIMEOUT -> LogLevel.ERROR;
            case REJECTED, DEGRADED -> LogLevel.WARN;
            case STARTED, SUCCESS -> LogLevel.INFO;
        };
    }

    private String normalized(final Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }
}
