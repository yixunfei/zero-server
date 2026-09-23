package group.zn.zero.runtime.kafka;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * production Kafka RPC 传输事件安全日志桥。
 *
 * <p>该桥只写入固定消息和枚举派生的低基数字段，不读取或复制事件携带的 message、attributes、
 * transportName、correlationId、serviceName、methodName、topic 或 group。TraceId 只取自事件
 * traceId，缺失时使用固定安全值；失败事件优先保留事件绑定的真实 {@link ErrorCode}。</p>
 *
 * <p>该类不创建线程、不修改业务数据；{@link #onEvent(RpcTransportEvent)} 同步调用注入的
 * {@link LogAppender}，线程安全性和阻塞特征由该 appender 保证。</p>
 *
 * @author zn
 */
public final class KafkaProductionTelemetryObserver implements RpcTransportObserver {

    /** 固定 Kafka 传输类型。 */
    private static final String TRANSPORT_TYPE = "kafka";
    /** 缺少 TraceId 时使用的固定安全值。 */
    private static final String UNTRACED = "rpc-transport-untraced";
    /** 固定安全日志消息。 */
    private static final String SAFE_MESSAGE = "production kafka rpc transport event";
    /** production Kafka RPC 日志来源。 */
    private static final LogSource LOG_SOURCE =
            new LogSource("zero-server", "production", "zero-rpc-kafka");
    /** 事件类型低基数字段缓存。 */
    private static final String[] EVENT_LABELS = lowercaseLabels(RpcTransportEventType.values());
    /** 结果低基数字段缓存。 */
    private static final String[] RESULT_LABELS = lowercaseLabels(LogResult.values());
    /** 事件操作名缓存。 */
    private static final String[] EVENT_OPERATIONS = prefixedLabels("kafka-rpc-", EVENT_LABELS);
    /** 安全日志写入端口。 */
    private final LogAppender logAppender;

    /**
     * 创建 production Kafka RPC 传输事件安全日志桥。
     *
     * @param logAppender 安全日志写入端口；不可为空；其线程安全性与阻塞特征由实现声明。
     * @throws NullPointerException 日志写入端口为空时抛出。
     */
    public KafkaProductionTelemetryObserver(final LogAppender logAppender) {
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
    }

    /**
     * 将单个 RPC 传输事件转换为不含事件原始业务值的结构化日志。
     *
     * <p>该方法不修改事件或业务数据。失败型事件缺少 ErrorCode 时，会按事件类型绑定稳定的
     * {@link RpcErrorCode}；成功和开始型事件不复制偶然携带的 ErrorCode。</p>
     *
     * @param event RPC 传输事件；不可为空。
     * @throws NullPointerException 事件为空时抛出。
     * @throws group.zn.zero.core.error.ZeroException 日志记录非法或 appender 写入失败时抛出。
     */
    @Override
    public void onEvent(final RpcTransportEvent event) {
        RpcTransportEvent current = Objects.requireNonNull(event, "event");
        RpcTransportEventType eventType = current.type();
        LogResult result = logResult(eventType);
        ErrorCode errorCode = errorCode(current, result);
        logAppender.append(ZeroLogRecord.create(
                current.time(),
                logLevel(result),
                logType(result),
                LOG_SOURCE,
                new LogOperation(EVENT_OPERATIONS[eventType.ordinal()], result, errorCode),
                traceId(current.traceId()),
                SAFE_MESSAGE,
                Map.of(
                        "eventType", EVENT_LABELS[eventType.ordinal()],
                        "result", RESULT_LABELS[result.ordinal()],
                        "transportType", TRANSPORT_TYPE)));
    }

    private LogResult logResult(final RpcTransportEventType eventType) {
        return switch (eventType) {
            case HANDLER_STARTED, PENDING_REGISTERED -> LogResult.STARTED;
            case REQUEST_REJECTED, PENDING_REJECTED -> LogResult.REJECTED;
            case HANDLER_FAILED, SEND_FAILED, PENDING_FAILED -> LogResult.FAILURE;
            case PENDING_TIMED_OUT -> LogResult.TIMEOUT;
            case CONSUMER_RESTARTING -> LogResult.DEGRADED;
            default -> LogResult.SUCCESS;
        };
    }

    private ErrorCode errorCode(final RpcTransportEvent event, final LogResult result) {
        if (result == LogResult.STARTED || result == LogResult.SUCCESS) {
            return null;
        }
        return event.errorCode() == null ? fallbackErrorCode(event.type()) : event.errorCode();
    }

    private ErrorCode fallbackErrorCode(final RpcTransportEventType eventType) {
        return switch (eventType) {
            case REQUEST_REJECTED -> RpcErrorCode.INVALID_REQUEST;
            case HANDLER_FAILED -> RpcErrorCode.HANDLER_FAILED;
            case PENDING_TIMED_OUT -> RpcErrorCode.REQUEST_TIMEOUT;
            case SEND_FAILED, PENDING_REJECTED, PENDING_FAILED, CONSUMER_RESTARTING ->
                RpcErrorCode.TRANSPORT_UNAVAILABLE;
            default -> throw new IllegalStateException("non-failure rpc transport event has no fallback error code");
        };
    }

    private LogLevel logLevel(final LogResult result) {
        return switch (result) {
            case STARTED, SUCCESS -> LogLevel.INFO;
            case REJECTED, DEGRADED -> LogLevel.WARN;
            case FAILURE, TIMEOUT -> LogLevel.ERROR;
        };
    }

    private LogType logType(final LogResult result) {
        return result == LogResult.STARTED || result == LogResult.SUCCESS
                ? LogType.RUNTIME
                : LogType.ERROR;
    }

    private String traceId(final String traceId) {
        return traceId.isBlank() ? UNTRACED : traceId;
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
}
