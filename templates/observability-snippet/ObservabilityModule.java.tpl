package {{packageName}}.observability;

import group.zn.zero.core.error.ErrorCode;
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
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 业务工程最小可观测性接入片段。
 *
 * <p>模块只依赖安全写入端口和指标注册表，不接触终端 LogSink，不创建线程或外部连接。</p>
 *
 * @author zn
 */
public final class ObservabilityModule {

    /** 模块操作计数定义。 */
    private static final MetricDefinition OPERATIONS_TOTAL = new MetricDefinition(
            "zero_game_operations_total",
            "模块操作次数",
            "operations",
            List.of("module", "operation", "result"));

    /** 统一安全日志写入端口。 */
    private final LogAppender logAppender;

    /** 指标注册表。 */
    private final MetricRegistry metricRegistry;

    /** 日志来源。 */
    private final LogSource source;

    /**
     * 创建并注册最小可观测性模块。
     *
     * @param logAppender Starter 暴露的安全日志写入端口；不可为空。
     * @param metricRegistry 指标注册表；不可为空。
     * @param serviceName 服务名；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @throws NullPointerException 当任一依赖或来源标识为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 当指标定义冲突时抛出。
     */
    public ObservabilityModule(
            final LogAppender logAppender,
            final MetricRegistry metricRegistry,
            final String serviceName,
            final String instanceId) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.metricRegistry = Objects.requireNonNull(metricRegistry, "metricRegistry");
        this.source = new LogSource(serviceName, instanceId, "observability");
        metricRegistry.register(OPERATIONS_TOTAL);
    }

    /**
     * 记录成功操作。
     *
     * <p>本方法同步写日志和内存/适配器指标；不修改业务状态。TraceId 只进入日志固定字段。</p>
     *
     * @param traceId 显式 TraceId；不可为空或空白。
     * @param operation 低基数操作名；不可为空或空白。
     * @throws group.zn.zero.core.error.ZeroException 当日志或指标契约被拒绝时抛出。
     */
    public void recordSuccess(final String traceId, final String operation) {
        append(traceId, operation, LogLevel.INFO, LogResult.SUCCESS, null, "operation completed");
        metricRegistry.record(new MetricSample(
                OPERATIONS_TOTAL.name(),
                1.0D,
                Map.of("module", "observability", "operation", operation, "result", "success"),
                Instant.now()));
    }

    /**
     * 记录失败操作。
     *
     * <p>本方法只记录可观测信号，不执行重试或改写业务结果。</p>
     *
     * @param traceId 显式 TraceId；不可为空或空白。
     * @param operation 低基数操作名；不可为空或空白。
     * @param errorCode 真实失败 ErrorCode；不可为空且不能为成功码。
     * @throws group.zn.zero.core.error.ZeroException 当日志或指标契约被拒绝时抛出。
     */
    public void recordFailure(
            final String traceId,
            final String operation,
            final ErrorCode errorCode) {
        append(traceId, operation, LogLevel.ERROR, LogResult.FAILURE, errorCode, "operation failed");
        metricRegistry.record(new MetricSample(
                OPERATIONS_TOTAL.name(),
                1.0D,
                Map.of("module", "observability", "operation", operation, "result", "failure"),
                Instant.now()));
    }

    private void append(
            final String traceId,
            final String operation,
            final LogLevel level,
            final LogResult result,
            final ErrorCode errorCode,
            final String message) {
        logAppender.append(ZeroLogRecord.create(
                Instant.now(),
                level,
                result == LogResult.FAILURE ? LogType.ERROR : LogType.BUSINESS,
                source,
                new LogOperation(operation, result, errorCode),
                traceId,
                message,
                Map.of("component", "observability")));
    }
}
