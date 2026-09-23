package group.zn.zero.runtime.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.runtime.kafka.KafkaProductionTelemetryObserver;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * production Kafka RPC 传输事件安全日志桥测试。
 *
 * @author zn
 */
class KafkaProductionTelemetryObserverTest {

    /** 用于反证禁止事件字段不会进入日志的敏感哨兵。 */
    private static final String SECRET = "PAF1-SECRET-SENTINEL";

    /**
     * 验证失败事件保留真实 ErrorCode，且所有禁止原值均不会进入日志记录的公开内容。
     */
    @Test
    void failureShouldKeepRealErrorCodeWithoutLeakingEventValues() {
        List<ZeroLogRecord> records = new ArrayList<>();
        KafkaProductionTelemetryObserver observer = new KafkaProductionTelemetryObserver(records::add);

        observer.onEvent(event(
                RpcTransportEventType.SEND_FAILED,
                "trace-approved",
                RpcErrorCode.CODEC_FAILED));

        ZeroLogRecord record = records.getFirst();
        assertEquals(Instant.parse("2026-08-05T00:00:00Z"), record.time());
        assertEquals(LogLevel.ERROR, record.level());
        assertEquals(LogType.ERROR, record.logType());
        assertEquals("kafka-rpc-send_failed", record.operation());
        assertEquals(LogResult.FAILURE, record.result());
        assertSame(RpcErrorCode.CODEC_FAILED, record.errorCode());
        assertEquals("trace-approved", record.traceId());
        assertEquals("production kafka rpc transport event", record.message());
        assertEquals(Map.of(
                "eventType", "send_failed",
                "result", "failure",
                "transportType", "kafka"), record.fields());
        assertEquals("zero-server", record.serviceName());
        assertEquals("production", record.instanceId());
        assertEquals("zero-rpc-kafka", record.module());
        assertNoSecret(record);
    }

    /**
     * 验证成功事件忽略偶然携带的 ErrorCode，空 TraceId 使用固定值且绝不回退到 correlationId。
     */
    @Test
    void successShouldUseSafeTraceFallbackAndIgnoreIncidentalErrorCode() {
        List<ZeroLogRecord> records = new ArrayList<>();
        KafkaProductionTelemetryObserver observer = new KafkaProductionTelemetryObserver(records::add);

        observer.onEvent(event(
                RpcTransportEventType.REQUEST_SENT,
                "",
                RpcErrorCode.SERVICE_NOT_FOUND));

        ZeroLogRecord record = records.getFirst();
        assertEquals(LogLevel.INFO, record.level());
        assertEquals(LogType.RUNTIME, record.logType());
        assertEquals("kafka-rpc-request_sent", record.operation());
        assertEquals(LogResult.SUCCESS, record.result());
        assertNull(record.errorCode());
        assertEquals("rpc-transport-untraced", record.traceId());
        assertFalse(record.traceId().contains(SECRET));
        assertNoSecret(record);
    }

    /**
     * 验证全部失败型事件缺码时使用稳定、语义匹配的框架 RPC ErrorCode。
     */
    @Test
    void failureEventsWithoutCodeShouldUseStableRpcFallbacks() {
        Map<RpcTransportEventType, ErrorCode> expectedCodes = new EnumMap<>(RpcTransportEventType.class);
        expectedCodes.put(RpcTransportEventType.REQUEST_REJECTED, RpcErrorCode.INVALID_REQUEST);
        expectedCodes.put(RpcTransportEventType.HANDLER_FAILED, RpcErrorCode.HANDLER_FAILED);
        expectedCodes.put(RpcTransportEventType.SEND_FAILED, RpcErrorCode.TRANSPORT_UNAVAILABLE);
        expectedCodes.put(RpcTransportEventType.PENDING_REJECTED, RpcErrorCode.TRANSPORT_UNAVAILABLE);
        expectedCodes.put(RpcTransportEventType.PENDING_FAILED, RpcErrorCode.TRANSPORT_UNAVAILABLE);
        expectedCodes.put(RpcTransportEventType.PENDING_TIMED_OUT, RpcErrorCode.REQUEST_TIMEOUT);
        expectedCodes.put(RpcTransportEventType.CONSUMER_RESTARTING, RpcErrorCode.TRANSPORT_UNAVAILABLE);
        List<ZeroLogRecord> records = new ArrayList<>();
        KafkaProductionTelemetryObserver observer = new KafkaProductionTelemetryObserver(records::add);

        for (RpcTransportEventType eventType : expectedCodes.keySet()) {
            observer.onEvent(event(eventType, "trace-approved", null));
        }

        assertEquals(expectedCodes.size(), records.size());
        for (ZeroLogRecord record : records) {
            String eventLabel = record.fields().get("eventType");
            RpcTransportEventType eventType = RpcTransportEventType.valueOf(eventLabel.toUpperCase());
            assertSame(expectedCodes.get(eventType), record.errorCode());
        }
    }

    /**
     * 验证每种事件的结果、等级、日志类型、操作名和低基数字段映射保持稳定。
     */
    @Test
    void allEventTypesShouldUseStableLowCardinalityMappings() {
        List<ZeroLogRecord> records = new ArrayList<>();
        KafkaProductionTelemetryObserver observer = new KafkaProductionTelemetryObserver(records::add);

        for (RpcTransportEventType eventType : RpcTransportEventType.values()) {
            LogResult result = expectedResult(eventType);
            ErrorCode errorCode = result == LogResult.STARTED || result == LogResult.SUCCESS
                    ? null
                    : RpcErrorCode.INVOCATION_FAILED;
            observer.onEvent(event(eventType, "trace-approved", errorCode));
        }

        assertEquals(RpcTransportEventType.values().length, records.size());
        for (int index = 0; index < records.size(); index++) {
            RpcTransportEventType eventType = RpcTransportEventType.values()[index];
            LogResult result = expectedResult(eventType);
            String eventLabel = eventType.name().toLowerCase(Locale.ROOT);
            ZeroLogRecord record = records.get(index);
            assertEquals(result, record.result());
            assertEquals(expectedLevel(result), record.level());
            assertEquals(expectedLogType(result), record.logType());
            assertEquals("kafka-rpc-" + eventLabel, record.operation());
            assertEquals(Map.of(
                    "eventType", eventLabel,
                    "result", result.name().toLowerCase(Locale.ROOT),
                    "transportType", "kafka"), record.fields());
            assertNoSecret(record);
        }
    }

    /**
     * 验证桥拒绝空依赖与空事件。
     */
    @Test
    void nullInputsShouldFailFast() {
        assertThrows(NullPointerException.class, () -> new KafkaProductionTelemetryObserver(null));
        KafkaProductionTelemetryObserver observer = new KafkaProductionTelemetryObserver(record -> { });
        assertThrows(NullPointerException.class, () -> observer.onEvent(null));
    }

    /**
     * 创建将全部禁止字符串字段和 attributes 键值填入同一敏感哨兵的事件。
     *
     * @param type 事件类型；不可为空。
     * @param traceId 唯一允许传入日志的 TraceId；不可为空但可为空串。
     * @param errorCode 可选真实错误码；可为空。
     * @return 不可变 RPC 传输事件；不可为空且线程安全。
     */
    private RpcTransportEvent event(
            final RpcTransportEventType type,
            final String traceId,
            final ErrorCode errorCode) {
        return new RpcTransportEvent(
                Instant.parse("2026-08-05T00:00:00Z"),
                type,
                SECRET,
                SECRET,
                traceId,
                SECRET,
                SECRET,
                SECRET,
                SECRET,
                errorCode,
                SECRET,
                Map.of(SECRET, SECRET));
    }

    /**
     * 返回测试冻结的事件结果映射。
     *
     * @param eventType 事件类型；不可为空。
     * @return 预期日志结果；不可为空。
     */
    private LogResult expectedResult(final RpcTransportEventType eventType) {
        return switch (eventType) {
            case HANDLER_STARTED, PENDING_REGISTERED -> LogResult.STARTED;
            case REQUEST_REJECTED, PENDING_REJECTED -> LogResult.REJECTED;
            case HANDLER_FAILED, SEND_FAILED, PENDING_FAILED -> LogResult.FAILURE;
            case PENDING_TIMED_OUT -> LogResult.TIMEOUT;
            case CONSUMER_RESTARTING -> LogResult.DEGRADED;
            default -> LogResult.SUCCESS;
        };
    }

    /**
     * 返回测试冻结的结果等级映射。
     *
     * @param result 日志结果；不可为空。
     * @return 预期日志等级；不可为空。
     */
    private LogLevel expectedLevel(final LogResult result) {
        return switch (result) {
            case STARTED, SUCCESS -> LogLevel.INFO;
            case REJECTED, DEGRADED -> LogLevel.WARN;
            case FAILURE, TIMEOUT -> LogLevel.ERROR;
        };
    }

    /**
     * 返回测试冻结的结果日志类型映射。
     *
     * @param result 日志结果；不可为空。
     * @return 预期日志类型；不可为空。
     */
    private LogType expectedLogType(final LogResult result) {
        return result == LogResult.STARTED || result == LogResult.SUCCESS
                ? LogType.RUNTIME
                : LogType.ERROR;
    }

    /**
     * 反证日志公开字段、消息、值对象和安全摘要均不包含敏感哨兵。
     *
     * @param record 待校验日志记录；不可为空。
     */
    private void assertNoSecret(final ZeroLogRecord record) {
        String exposed = record.message()
                + record.fields()
                + record.traceId()
                + record.source()
                + record.logOperation()
                + record;
        assertFalse(exposed.contains(SECRET), exposed);
    }
}
