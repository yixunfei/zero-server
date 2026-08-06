package group.zn.zero.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ErrorCategory;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 统一日志记录字段、预算、不可变性与 ErrorCode 组合测试。
 *
 * @author zn
 */
class ZeroLogRecordTest {

    /**
     * 验证首版固定字段、显式值对象和确定字段顺序。
     */
    @Test
    void recordShouldExposeVersionedFixedFieldsInStableForm() {
        LinkedHashMap<String, String> mutableFields = new LinkedHashMap<>();
        mutableFields.put("z", "last");
        mutableFields.put("a", "first");

        ZeroLogRecord record = ZeroLogRecord.create(
                Instant.parse("2026-08-04T00:00:00Z"),
                LogLevel.INFO,
                LogType.RUNTIME,
                new LogSource("game-service", "instance-1", "zero-log"),
                new LogOperation("runtime.start", LogResult.STARTED, null),
                "  trace-1  ",
                "starting",
                mutableFields);
        mutableFields.put("leak", "late mutation");

        assertEquals("1", record.schemaVersion());
        assertEquals(Instant.parse("2026-08-04T00:00:00Z"), record.time());
        assertEquals(LogLevel.INFO, record.level());
        assertEquals(LogType.RUNTIME, record.logType());
        assertEquals("game-service", record.serviceName());
        assertEquals("instance-1", record.instanceId());
        assertEquals("zero-log", record.module());
        assertEquals("runtime.start", record.operation());
        assertEquals(LogResult.STARTED, record.result());
        assertEquals("trace-1", record.traceId());
        assertNull(record.errorCode());
        assertEquals("starting", record.message());
        assertEquals(List.of("a", "z"), new ArrayList<>(record.fields().keySet()));
        assertThrows(UnsupportedOperationException.class,
                () -> record.fields().put("illegal", "mutation"));
    }

    /**
     * 验证 withField 只创建新记录，不修改原记录，并保持字段排序。
     */
    @Test
    void withFieldShouldReturnIndependentSortedRecord() {
        ZeroLogRecord original = successRecord(Map.of("b", "2"));

        ZeroLogRecord updated = original.withField("a", "1");

        assertNotSame(original, updated);
        assertEquals(Map.of("b", "2"), original.fields());
        assertEquals(List.of("a", "b"), new ArrayList<>(updated.fields().keySet()));
    }

    /**
     * 验证唯一 ErrorCode 判定式接受所有有效边界组合。
     */
    @Test
    void errorCodeRuleShouldAcceptValidCombinations() {
        create(LogLevel.INFO, LogType.BUSINESS, LogResult.STARTED, null);
        create(LogLevel.INFO, LogType.BUSINESS, LogResult.SUCCESS, null);
        create(LogLevel.INFO, LogType.BUSINESS, LogResult.FAILURE, SystemErrorCode.SYSTEM_ERROR);
        create(LogLevel.WARN, LogType.SECURITY, LogResult.REJECTED, SystemErrorCode.SYSTEM_ERROR);
        create(LogLevel.ERROR, LogType.BUSINESS, LogResult.FAILURE, SystemErrorCode.SYSTEM_ERROR);
        create(LogLevel.WARN, LogType.ERROR, LogResult.TIMEOUT, SystemErrorCode.SYSTEM_ERROR);
        create(LogLevel.WARN, LogType.PERFORMANCE, LogResult.DEGRADED, SystemErrorCode.SYSTEM_ERROR);
    }

    /**
     * 验证失败缺码、成功或开始带码以及成功与 ERROR 冲突均被统一拒绝。
     */
    @Test
    void errorCodeRuleShouldRejectInvalidCombinations() {
        assertInvalid(() -> create(LogLevel.INFO, LogType.BUSINESS, LogResult.FAILURE, null));
        assertInvalid(() -> create(LogLevel.INFO, LogType.BUSINESS, LogResult.REJECTED, null));
        assertInvalid(() -> create(LogLevel.INFO, LogType.BUSINESS, LogResult.TIMEOUT, null));
        assertInvalid(() -> create(LogLevel.INFO, LogType.BUSINESS, LogResult.DEGRADED, null));
        assertInvalid(() -> create(
                LogLevel.INFO, LogType.BUSINESS, LogResult.STARTED, SystemErrorCode.SYSTEM_ERROR));
        assertInvalid(() -> create(
                LogLevel.INFO, LogType.BUSINESS, LogResult.SUCCESS, SystemErrorCode.SYSTEM_ERROR));
        assertInvalid(() -> create(LogLevel.ERROR, LogType.BUSINESS, LogResult.SUCCESS, null));
        assertInvalid(() -> create(LogLevel.INFO, LogType.ERROR, LogResult.SUCCESS, null));
        assertInvalid(() -> create(LogLevel.ERROR, LogType.BUSINESS, LogResult.STARTED, null));
    }

    /**
     * 验证字段数量、键值、消息和总字符预算的边界值。
     */
    @Test
    void recordShouldEnforceFieldBudgets() {
        LinkedHashMap<String, String> thirtyTwoFields = new LinkedHashMap<>();
        for (int index = 0; index < 32; index++) {
            thirtyTwoFields.put("field" + index, "value");
        }
        successRecord(thirtyTwoFields);
        successRecord(Map.of("k".repeat(64), "v".repeat(4096)));
        successRecord(Map.of("field", "value"), "m".repeat(4096));
        successRecord(Map.of(
                "aaa", "v".repeat(4093),
                "bbb", "v".repeat(4093),
                "ccc", "v".repeat(4093)), "m".repeat(4096));

        LinkedHashMap<String, String> thirtyThreeFields = new LinkedHashMap<>(thirtyTwoFields);
        thirtyThreeFields.put("field32", "value");
        assertInvalid(() -> successRecord(thirtyThreeFields));
        assertInvalid(() -> successRecord(Map.of("", "value")));
        assertInvalid(() -> successRecord(Map.of("k".repeat(65), "value")));
        assertInvalid(() -> successRecord(Map.of("field", "v".repeat(4097))));
        assertInvalid(() -> successRecord(Map.of(), "m".repeat(4097)));
        assertInvalid(() -> successRecord(Map.of(
                "aaa", "v".repeat(4094),
                "bbb", "v".repeat(4093),
                "ccc", "v".repeat(4093)), "m".repeat(4096)));
        Map<String, String> nullValue = new HashMap<>();
        nullValue.put("field", null);
        assertInvalid(() -> successRecord(nullValue));
    }

    /**
     * 验证固定标识长度、空白和控制字符约束。
     */
    @Test
    void recordShouldEnforceFixedIdentifierBoundaries() {
        ZeroLogRecord.create(
                Instant.EPOCH,
                LogLevel.INFO,
                LogType.RUNTIME,
                new LogSource("s".repeat(128), "i".repeat(128), "m".repeat(128)),
                new LogOperation("o".repeat(128), LogResult.SUCCESS, null),
                "t".repeat(128),
                "ok",
                Map.of());

        assertInvalid(() -> ZeroLogRecord.create(
                Instant.EPOCH,
                LogLevel.INFO,
                LogType.RUNTIME,
                new LogSource("s".repeat(129), "instance", "module"),
                new LogOperation("operation", LogResult.SUCCESS, null),
                "trace",
                "ok",
                Map.of()));
        assertInvalid(() -> createWithTrace(" "));
        assertInvalid(() -> createWithTrace("trace\ninvalid"));
        assertInvalid(() -> successRecord(Map.of("bad\u0000key", "value")));
    }

    /**
     * 验证日志模块错误码的类别、稳定字符串和默认说明。
     */
    @Test
    void logErrorCodesShouldRemainStable() {
        assertCode(LogErrorCode.INVALID_RECORD,
                "ZERO-LOG-INVALID-RECORD", "log record is invalid");
        assertCode(LogErrorCode.SENSITIVE_FIELD_REJECTED,
                "ZERO-LOG-SENSITIVE-FIELD-REJECTED", "sensitive log field was rejected");
        assertCode(LogErrorCode.PROCESSOR_FAILED,
                "ZERO-LOG-PROCESSOR-FAILED", "log processor failed");
        assertCode(LogErrorCode.SINK_FAILED,
                "ZERO-LOG-SINK-FAILED", "log sink failed");
    }

    /**
     * 创建指定结果组合的日志。
     *
     * @param level 日志等级；不可为空。
     * @param type 日志类型；不可为空。
     * @param result 操作结果；不可为空。
     * @param errorCode 错误码；可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord create(
            final LogLevel level,
            final LogType type,
            final LogResult result,
            final group.zn.zero.core.error.ErrorCode errorCode) {
        return ZeroLogRecord.create(
                Instant.EPOCH,
                level,
                type,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation("record.test", result, errorCode),
                "trace",
                "message",
                Map.of());
    }

    /**
     * 创建成功日志。
     *
     * @param fields 字段；不可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord successRecord(final Map<String, String> fields) {
        return successRecord(fields, "message");
    }

    /**
     * 创建指定消息的成功日志。
     *
     * @param fields 字段；不可为空。
     * @param message 消息；不可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord successRecord(final Map<String, String> fields, final String message) {
        return ZeroLogRecord.create(
                Instant.EPOCH,
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation("record.test", LogResult.SUCCESS, null),
                "trace",
                message,
                fields);
    }

    /**
     * 创建指定 TraceId 的成功日志。
     *
     * @param traceId TraceId；不可为空。
     * @return 日志记录；不可为空；线程安全。
     */
    private ZeroLogRecord createWithTrace(final String traceId) {
        return ZeroLogRecord.create(
                Instant.EPOCH,
                LogLevel.INFO,
                LogType.BUSINESS,
                new LogSource("game-service", "local-1", "zero-log"),
                new LogOperation("record.test", LogResult.SUCCESS, null),
                traceId,
                "message",
                Map.of());
    }

    /**
     * 断言构造失败绑定统一非法记录错误码。
     *
     * @param executable 待执行逻辑；不可为空。
     */
    private void assertInvalid(final org.junit.jupiter.api.function.Executable executable) {
        ZeroException exception = assertThrows(ZeroException.class, executable);
        assertEquals(LogErrorCode.INVALID_RECORD, exception.errorCode());
    }

    /**
     * 断言错误码冻结值。
     *
     * @param errorCode 错误码；不可为空。
     * @param code 稳定码；不可为空。
     * @param message 默认说明；不可为空。
     */
    private void assertCode(final LogErrorCode errorCode, final String code, final String message) {
        assertEquals(ErrorCategory.SYSTEM, errorCode.category());
        assertEquals(code, errorCode.code());
        assertEquals(message, errorCode.message());
    }
}
