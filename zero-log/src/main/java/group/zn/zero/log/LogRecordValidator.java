package group.zn.zero.log;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Map;

/**
 * 统一日志记录结构、预算和 ErrorCode 组合校验器。
 *
 * @author zn
 */
final class LogRecordValidator {

    /**
     * 工具类不允许实例化。
     */
    private LogRecordValidator() {
    }

    /**
     * 校验完整日志记录。
     *
     * @param record 日志记录；不可为空。
     * @throws ZeroException 结构、预算或结果组合非法时抛出。
     */
    static void requireValid(final ZeroLogRecord record) {
        if (record == null) {
            throw invalid("record must not be null", null);
        }
        requireStandardFields(record);
        requireErrorCodeCombination(record);
        requireContentBudget(record.message(), record.fields());
    }

    /**
     * 规范化并校验固定标识。
     *
     * @param value 标识原值；不可为空。
     * @param fieldName 字段名；不可为空。
     * @return trim 后标识；不可为空。
     * @throws ZeroException 标识为空、超长或包含控制字符时抛出。
     */
    static String normalizeIdentifier(final String value, final String fieldName) {
        if (value == null) {
            throw invalid(fieldName + " must not be null", null);
        }
        String normalized = value.trim();
        if (normalized.isEmpty()
                || normalized.length() > ZeroLogRecord.MAX_IDENTIFIER_LENGTH
                || LogTextEscaper.containsControl(normalized)) {
            throw invalid(fieldName + " is invalid", null);
        }
        return normalized;
    }

    /**
     * 校验结果不为空。
     *
     * @param result 结果；不可为空。
     * @throws ZeroException 结果为空时抛出。
     */
    static void requireResult(final LogResult result) {
        if (result == null) {
            throw invalid("result must not be null", null);
        }
    }

    /**
     * 校验可选错误码自身结构。
     *
     * @param errorCode 错误码；可为空。
     * @throws ZeroException 错误码属性为空、超长或包含控制字符时抛出。
     */
    static void requireErrorCodeShape(final ErrorCode errorCode) {
        if (errorCode == null) {
            return;
        }
        try {
            if (errorCode.category() == null || errorCode.message() == null) {
                throw invalid("errorCode metadata is invalid", null);
            }
            normalizeIdentifier(errorCode.code(), "errorCode");
        } catch (ZeroException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw invalid("errorCode metadata is invalid", exception);
        }
    }

    /**
     * 校验固定字段。
     *
     * @param record 日志记录；不可为空。
     * @throws ZeroException 固定字段非法时抛出。
     */
    private static void requireStandardFields(final ZeroLogRecord record) {
        if (record.time() == null || record.level() == null || record.logType() == null
                || record.source() == null || record.logOperation() == null) {
            throw invalid("fixed log fields must not be null", null);
        }
        normalizeIdentifier(record.serviceName(), "serviceName");
        normalizeIdentifier(record.instanceId(), "instanceId");
        normalizeIdentifier(record.module(), "module");
        normalizeIdentifier(record.operation(), "operation");
        normalizeIdentifier(record.traceId(), "traceId");
        requireResult(record.result());
        requireErrorCodeShape(record.errorCode());
        if (record.message() == null || record.fields() == null) {
            throw invalid("message and fields must not be null", null);
        }
    }

    /**
     * 校验 ErrorCode 唯一判定式和冲突组合。
     *
     * @param record 日志记录；不可为空。
     * @throws ZeroException 结果组合非法时抛出。
     */
    private static void requireErrorCodeCombination(final ZeroLogRecord record) {
        boolean errorAxis = record.level() == LogLevel.ERROR || record.logType() == LogType.ERROR;
        boolean failureResult = switch (record.result()) {
            case FAILURE, REJECTED, TIMEOUT, DEGRADED -> true;
            case STARTED, SUCCESS -> false;
        };
        boolean requiresErrorCode = errorAxis || failureResult;
        boolean nonErrorResult = record.result() == LogResult.STARTED || record.result() == LogResult.SUCCESS;
        if (record.result() == LogResult.SUCCESS && errorAxis
                || record.result() == LogResult.STARTED && errorAxis
                || requiresErrorCode && record.errorCode() == null
                || nonErrorResult && record.errorCode() != null) {
            throw invalid("level, logType, result and errorCode conflict", null);
        }
    }

    /**
     * 校验消息和字段预算。
     *
     * @param message 日志消息；不可为空。
     * @param fields 有序字段；不可为空。
     * @throws ZeroException 任一长度、数量或控制字符约束非法时抛出。
     */
    private static void requireContentBudget(final String message, final Map<String, String> fields) {
        if (message.length() > ZeroLogRecord.MAX_MESSAGE_LENGTH
                || fields.size() > ZeroLogRecord.MAX_FIELDS) {
            throw invalid("message or field count exceeds budget", null);
        }
        int totalLength = message.length();
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null || value == null
                    || key.isEmpty()
                    || key.length() > ZeroLogRecord.MAX_FIELD_KEY_LENGTH
                    || value.length() > ZeroLogRecord.MAX_FIELD_VALUE_LENGTH
                    || LogTextEscaper.containsControl(key)) {
                throw invalid("log field is invalid", null);
            }
            totalLength = addWithinBudget(totalLength, key.length());
            totalLength = addWithinBudget(totalLength, value.length());
        }
    }

    /**
     * 有界累加总内容字符数。
     *
     * @param current 当前字符数。
     * @param additional 新增字符数。
     * @return 累加结果。
     * @throws ZeroException 累加后超过总预算时抛出。
     */
    private static int addWithinBudget(final int current, final int additional) {
        if (additional > ZeroLogRecord.MAX_CONTENT_LENGTH - current) {
            throw invalid("message and fields exceed total budget", null);
        }
        return current + additional;
    }

    /**
     * 创建统一非法记录异常。
     *
     * @param message 安全诊断说明；不可为空。
     * @param cause 原始异常；可为空。
     * @return 统一异常；不可为空。
     */
    private static ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(LogErrorCode.INVALID_RECORD, message, cause);
    }
}
