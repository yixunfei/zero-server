package group.zn.zero.log;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.monitor.AlertEvent;
import group.zn.zero.monitor.AlertSeverity;
import group.zn.zero.monitor.AlertSink;
import group.zn.zero.monitor.MonitorErrorCode;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * 把监控告警适配到统一日志安全入口的 AlertSink。
 *
 * <p>该类不持有终端 {@link LogSink}，所有告警都必须经过调用方注入的 {@link LogAppender}。
 * 告警消息和标签不在适配器内绕过或替代统一敏感安全门。
 *
 * @author zn
 */
public final class MonitorAlertLogSink implements AlertSink {

    /**
     * 告警日志操作名。
     */
    private static final String OPERATION = "monitor.alert.publish";

    /**
     * 统一安全写入端口。
     */
    private final LogAppender logAppender;

    /**
     * 告警日志来源。
     */
    private final LogSource source;

    /**
     * 显式 TraceId。
     */
    private final String traceId;

    /**
     * 创建监控告警日志适配器。
     *
     * <p>实例不修改传入告警；线程安全性由 appender 保证。TraceId 在构造时按日志固定标识规则校验，
     * 不从 ThreadLocal 或其他隐式上下文读取。
     *
     * @param logAppender 统一日志安全入口；不可为空。
     * @param source 告警日志来源；不可为空。
     * @param traceId 显式 TraceId；不可为空或空白。
     * @throws NullPointerException appender 或来源为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException TraceId 非法时抛出。
     */
    public MonitorAlertLogSink(
            final LogAppender logAppender,
            final LogSource source,
            final String traceId) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.source = Objects.requireNonNull(source, "source");
        this.traceId = LogRecordValidator.normalizeIdentifier(traceId, "traceId");
    }

    /**
     * 同步写入告警事件。
     *
     * <p>INFO / WARNING 表达“告警日志写入动作成功”，不伪造 ErrorCode；CRITICAL 使用 ERROR
     * 级别、ERROR 类型、FAILURE 结果并绑定专用监控错误码。
     *
     * @param event 告警事件；不可为空；不会被修改。
     * @throws NullPointerException 告警事件为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 日志校验、安全门或终端写入失败时抛出。
     */
    @Override
    public void publish(final AlertEvent event) {
        AlertEvent current = Objects.requireNonNull(event, "event");
        AlertSeverity severity = current.severity();
        logAppender.append(ZeroLogRecord.create(
                current.time(),
                logLevelOf(severity),
                logTypeOf(severity),
                source,
                operationOf(severity),
                traceId,
                current.message(),
                fieldsOf(current)));
    }

    /**
     * 映射告警等级。
     *
     * @param severity 告警等级；不可为空。
     * @return 显式日志等级；不可为空。
     */
    private LogLevel logLevelOf(final AlertSeverity severity) {
        return switch (severity) {
            case INFO -> LogLevel.INFO;
            case WARNING -> LogLevel.WARN;
            case CRITICAL -> LogLevel.ERROR;
        };
    }

    /**
     * 映射告警日志业务分类。
     *
     * @param severity 告警等级；不可为空。
     * @return CRITICAL 为 ERROR，其他为 PERFORMANCE。
     */
    private LogType logTypeOf(final AlertSeverity severity) {
        return severity == AlertSeverity.CRITICAL ? LogType.ERROR : LogType.PERFORMANCE;
    }

    /**
     * 创建告警日志操作结果。
     *
     * @param severity 告警等级；不可为空。
     * @return 操作值对象；不可为空。
     */
    private LogOperation operationOf(final AlertSeverity severity) {
        boolean critical = severity == AlertSeverity.CRITICAL;
        ErrorCode errorCode = critical ? MonitorErrorCode.CRITICAL_ALERT_TRIGGERED : null;
        return new LogOperation(
                OPERATION,
                critical ? LogResult.FAILURE : LogResult.SUCCESS,
                errorCode);
    }

    /**
     * 创建告警白名单字段。
     *
     * @param event 告警事件；不可为空。
     * @return 按插入顺序构建、可能为空标签扩展的 Map；不可为空；调用方会防御性复制。
     */
    private Map<String, String> fieldsOf(final AlertEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("alert.ruleName", event.ruleName());
        fields.put("metric.name", event.metricName());
        fields.put("alert.severity", event.severity().name());
        fields.put("observed.value", String.valueOf(event.value()));
        fields.put("threshold", String.valueOf(event.threshold()));
        event.labels().forEach((key, value) -> fields.put("metric.label." + key, value));
        return fields;
    }
}
