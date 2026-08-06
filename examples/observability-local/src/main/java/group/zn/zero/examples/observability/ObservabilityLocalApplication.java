package group.zn.zero.examples.observability;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.log.InMemoryLogSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogErrorCode;
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
import group.zn.zero.monitor.MonitorErrorCode;
import group.zn.zero.monitor.PrometheusExporter;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * 无 Docker 的最小可观测性正反路径示例。
 *
 * <p>示例同步运行统一日志安全管线和内存指标注册表，不创建线程、端口或外部连接。
 * 它展示五类结构化日志、真实失败 ErrorCode、显式 TraceId、有序指标 schema，
 * 以及敏感字段和高基数标签的运行时拒绝。</p>
 *
 * @author zn
 */
public final class ObservabilityLocalApplication {

    /** 固定示例时间，保证测试与输出可复现。 */
    private static final Instant DEMO_TIME = Instant.parse("2026-08-04T00:00:00Z");

    /** 显式示例 TraceId，只进入日志固定字段。 */
    private static final String TRACE_ID = "trace-observability-local";

    /** 示例日志来源。 */
    private static final LogSource SOURCE = new LogSource(
            "observability-local",
            "local-1",
            "example-observability");

    /** 示例低基数指标定义。 */
    private static final MetricDefinition OPERATIONS_TOTAL = new MetricDefinition(
            "zero_example_observability_operations_total",
            "本地可观测性示例操作次数",
            "operations",
            List.of("module", "operation", "result"));

    private ObservabilityLocalApplication() {
    }

    /**
     * 运行本地可观测性示例并输出稳定摘要。
     *
     * <p>本方法不启动后台资源，返回前全部同步动作均已结束。</p>
     *
     * @param args 命令行参数；当前不读取。
     */
    public static void main(final String[] args) {
        System.out.println(runDemo().summary());
    }

    /**
     * 执行成功日志、失败日志、审计日志、安全日志、性能日志和指标正反路径。
     *
     * <p>方法只修改本地内存 sink 与 registry，返回不可变快照；无外部 IO、线程或残留生命周期资源。</p>
     *
     * @return 示例结果；不可为空；日志列表有序、不可变、非空且线程安全。
     * @throws IllegalStateException 当预期安全拒绝未发生或错误码不符合契约时抛出。
     */
    public static DemoResult runDemo() {
        InMemoryLogSink terminalSink = new InMemoryLogSink();
        LogPipeline logAppender = new LogPipeline(List.of(), terminalSink);
        InMemoryMetricRegistry metricRegistry = new InMemoryMetricRegistry();
        metricRegistry.register(OPERATIONS_TOTAL);

        appendStandardLogs(logAppender);
        ErrorCode sensitiveRejection = rejectSensitiveField(logAppender);
        appendSecurityRejection(logAppender, sensitiveRejection);
        recordValidMetric(metricRegistry);
        ErrorCode labelRejection = rejectHighCardinalityLabel();

        return new DemoResult(
                terminalSink.records(),
                new PrometheusExporter().export(metricRegistry),
                sensitiveRejection,
                labelRejection,
                true);
    }

    private static void appendStandardLogs(final LogAppender logAppender) {
        logAppender.append(record(
                LogLevel.INFO,
                LogType.BUSINESS,
                "example.business",
                LogResult.SUCCESS,
                null,
                "business operation completed",
                Map.of("feature", "observability")));
        logAppender.append(record(
                LogLevel.ERROR,
                LogType.ERROR,
                "example.failure",
                LogResult.FAILURE,
                SystemErrorCode.SYSTEM_ERROR,
                "controlled failure example",
                Map.of("failureKind", "controlled")));
        logAppender.append(record(
                LogLevel.INFO,
                LogType.AUDIT,
                "example.audit",
                LogResult.STARTED,
                null,
                "audit operation started",
                Map.of("phase", "BEFORE")));
        logAppender.append(record(
                LogLevel.INFO,
                LogType.PERFORMANCE,
                "example.performance",
                LogResult.SUCCESS,
                null,
                "performance sample completed",
                Map.of("latencyBucket", "under_1ms")));
    }

    private static ErrorCode rejectSensitiveField(final LogAppender logAppender) {
        try {
            logAppender.append(record(
                    LogLevel.INFO,
                    LogType.BUSINESS,
                    "example.sensitive",
                    LogResult.SUCCESS,
                    null,
                    "this record must be rejected",
                    Map.of("token", "never-log-this-token")));
        } catch (ZeroException ex) {
            if (ex.errorCode() == LogErrorCode.SENSITIVE_FIELD_REJECTED) {
                return ex.errorCode();
            }
            throw ex;
        }
        throw new IllegalStateException("sensitive field was not rejected");
    }

    private static void appendSecurityRejection(
            final LogAppender logAppender,
            final ErrorCode rejectionCode) {
        logAppender.append(record(
                LogLevel.WARN,
                LogType.SECURITY,
                "example.security",
                LogResult.REJECTED,
                rejectionCode,
                "sensitive field rejected",
                Map.of("policy", "default-sensitive-field-policy")));
    }

    private static void recordValidMetric(final InMemoryMetricRegistry metricRegistry) {
        metricRegistry.record(new MetricSample(
                OPERATIONS_TOTAL.name(),
                1.0D,
                Map.of(
                        "module", "example",
                        "operation", "run",
                        "result", "success"),
                DEMO_TIME));
    }

    private static ErrorCode rejectHighCardinalityLabel() {
        try {
            new MetricSample(
                    OPERATIONS_TOTAL.name(),
                    1.0D,
                    Map.of("traceId", TRACE_ID),
                    DEMO_TIME);
        } catch (ZeroException ex) {
            if (ex.errorCode() == MonitorErrorCode.METRIC_LABEL_FORBIDDEN) {
                return ex.errorCode();
            }
            throw ex;
        }
        throw new IllegalStateException("high-cardinality label was not rejected");
    }

    private static ZeroLogRecord record(
            final LogLevel level,
            final LogType logType,
            final String operation,
            final LogResult result,
            final ErrorCode errorCode,
            final String message,
            final Map<String, String> fields) {
        return ZeroLogRecord.create(
                DEMO_TIME,
                level,
                logType,
                SOURCE,
                new LogOperation(operation, result, errorCode),
                TRACE_ID,
                message,
                fields);
    }

    /**
     * 本地可观测性示例结果。
     *
     * @param logs 已安全落地的日志快照；不可为空；有序、不可变、非空且线程安全。
     * @param prometheusText Prometheus 文本快照；不可为空。
     * @param sensitiveRejectionCode 敏感字段拒绝码；不可为空。
     * @param labelRejectionCode 高基数标签拒绝码；不可为空。
     * @param stopped 示例是否已无残留生命周期资源。
     * @author zn
     */
    public record DemoResult(
            List<ZeroLogRecord> logs,
            String prometheusText,
            ErrorCode sensitiveRejectionCode,
            ErrorCode labelRejectionCode,
            boolean stopped) {

        /**
         * 创建不可变示例结果。
         *
         * @throws NullPointerException 当日志、文本或 ErrorCode 为空时抛出。
         */
        public DemoResult {
            logs = List.copyOf(logs);
            java.util.Objects.requireNonNull(prometheusText, "prometheusText");
            java.util.Objects.requireNonNull(sensitiveRejectionCode, "sensitiveRejectionCode");
            java.util.Objects.requireNonNull(labelRejectionCode, "labelRejectionCode");
        }

        /**
         * 返回适合本地 smoke 验收的稳定摘要。
         *
         * @return 单行摘要；不可为空；线程安全。
         */
        public String summary() {
            return "zero-observability-local=ok"
                    + "|logs=" + logs.size()
                    + "|sensitiveRejected=" + sensitiveRejectionCode.code()
                    + "|labelRejected=" + labelRejectionCode.code()
                    + "|stopped=" + stopped;
        }
    }
}
