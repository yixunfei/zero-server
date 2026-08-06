package group.zn.zero.log;

import group.zn.zero.core.error.ZeroException;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;

/**
 * 组合不可关闭默认策略、附加收紧策略、标识脱敏和控制字符转义的安全门。
 *
 * @author zn
 */
final class LogSafetyGate {

    /**
     * 主消息的稳定 provenance 位置，避免每条日志重复创建位置对象。
     */
    private static final RedactionLocation MESSAGE_LOCATION = new RedactionLocation(true, "");

    /**
     * 默认不可关闭策略。
     */
    private final SensitiveFieldPolicy baselinePolicy;

    /**
     * 调用方附加收紧策略。
     */
    private final SensitiveFieldPolicy additionalPolicy;

    /**
     * 标识脱敏器。
     */
    private final LogIdentifierRedactor redactor;

    /**
     * 创建日志安全门。
     *
     * @param additionalPolicy 附加策略；不可为空。
     * @param redactor 标识脱敏器；不可为空。
     * @throws NullPointerException 参数为空时抛出。
     */
    LogSafetyGate(
            final SensitiveFieldPolicy additionalPolicy,
            final LogIdentifierRedactor redactor) {
        this.baselinePolicy = DefaultSensitiveFieldPolicy.instance();
        this.additionalPolicy = java.util.Objects.requireNonNull(additionalPolicy, "additionalPolicy");
        this.redactor = java.util.Objects.requireNonNull(redactor, "redactor");
    }

    /**
     * 在没有 processor 时执行唯一一次终端清洗。
     *
     * <p>该路径不创建 provenance Map 或包装结果；没有脱敏和控制字符变化时返回原记录。方法不修改
     * 原记录、不执行外部 IO，也不读取线程上下文。</p>
     *
     * @param record 已完成结构校验的记录；不可为空。
     * @return 原记录或清洗后的不可变记录；不可为空且线程安全。
     * @throws ZeroException 敏感字段、策略、脱敏结果或转义后预算非法时抛出。
     */
    ZeroLogRecord sanitizeTerminal(final ZeroLogRecord record) {
        return sanitizeWithoutProvenance(record, Map.of());
    }

    /**
     * 在 processor 前清洗记录并仅为实际脱敏位置生成可信 provenance。
     *
     * <p>没有脱敏时复用共享空 Map；首次出现脱敏时才创建内部引用表。该状态只在当前管线调用栈内
     * 流转，不写入公开日志记录。</p>
     *
     * @param record 已完成结构校验的记录；不可为空。
     * @return 清洗记录及本轮可信脱敏引用；不可为空且线程安全。
     * @throws ZeroException 敏感字段、策略、脱敏结果或转义后预算非法时抛出。
     */
    SanitizationResult sanitizeBeforeProcessors(final ZeroLogRecord record) {
        Map<RedactionLocation, String> generatedReferences = null;
        SanitizedValue sanitizedMessage = sanitizeValue("message", record.message(), Map.of(), true);
        if (sanitizedMessage.redacted()) {
            generatedReferences = addReference(
                    generatedReferences, MESSAGE_LOCATION, sanitizedMessage.value());
        }
        NavigableMap<String, String> sanitizedFields = null;
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            SanitizedValue sanitizedValue = sanitizeValue(
                    entry.getKey(), entry.getValue(), Map.of(), false);
            if (sanitizedValue.redacted()) {
                generatedReferences = addReference(
                        generatedReferences,
                        RedactionLocation.forField(entry.getKey()),
                        sanitizedValue.value());
            }
            if (!sanitizedValue.value().equals(entry.getValue())) {
                if (sanitizedFields == null) {
                    sanitizedFields = new TreeMap<>(record.fields());
                }
                sanitizedFields.put(entry.getKey(), sanitizedValue.value());
            }
        }
        ZeroLogRecord sanitizedRecord = record.withSanitizedContent(sanitizedMessage.value(), sanitizedFields);
        Map<RedactionLocation, String> immutableReferences = generatedReferences == null
                ? Map.of()
                : Map.copyOf(generatedReferences);
        return new SanitizationResult(sanitizedRecord, immutableReferences);
    }

    /**
     * 使用首次安全门的内部 provenance 执行 processor 后终端复验。
     *
     * <p>只有同一字段位置、同一值且确由本次首次安全门生成的引用才可免于再次 HMAC；调用方输入
     * 或 processor 伪造的相同外形字符串仍会作为原值重新脱敏。终端门不再生成后续无人消费的
     * provenance。</p>
     *
     * @param record processor 输出记录；不可为空。
     * @param trusted 首次安全门结果；不可为空。
     * @return 原记录或终端清洗后的不可变记录；不可为空且线程安全。
     * @throws ZeroException 敏感字段、策略、脱敏结果或转义后预算非法时抛出。
     */
    ZeroLogRecord sanitizeAfterProcessors(
            final ZeroLogRecord record,
            final SanitizationResult trusted) {
        SanitizationResult checked = java.util.Objects.requireNonNull(trusted, "trusted");
        return sanitizeWithoutProvenance(record, checked.redactionReferences());
    }

    /**
     * 清洗记录且不生成新的 provenance。
     *
     * @param record 待清洗记录；不可为空。
     * @param trustedReferences 当前管线首次门生成的可信引用；不可为空、不可变且可能为空。
     * @return 原记录或清洗后的不可变记录；不可为空且线程安全。
     * @throws ZeroException 敏感字段、策略、脱敏结果或转义后预算非法时抛出。
     */
    private ZeroLogRecord sanitizeWithoutProvenance(
            final ZeroLogRecord record,
            final Map<RedactionLocation, String> trustedReferences) {
        SanitizedValue sanitizedMessage = sanitizeValue(
                "message", record.message(), trustedReferences, true);
        NavigableMap<String, String> sanitizedFields = null;
        for (Map.Entry<String, String> entry : record.fields().entrySet()) {
            SanitizedValue sanitizedValue = sanitizeValue(
                    entry.getKey(), entry.getValue(), trustedReferences, false);
            if (!sanitizedValue.value().equals(entry.getValue())) {
                if (sanitizedFields == null) {
                    sanitizedFields = new TreeMap<>(record.fields());
                }
                sanitizedFields.put(entry.getKey(), sanitizedValue.value());
            }
        }
        return record.withSanitizedContent(sanitizedMessage.value(), sanitizedFields);
    }

    /**
     * 清洗单个消息或字段值。
     *
     * @param fieldPath 字段路径；不可为空。
     * @param value 原值；不可为空。
     * @param trustedReferences 首次门生成的可信引用；不可为空、不可变且可能为空。
     * @param message 是否正在处理主消息。
     * @return 安全值及其是否为可信脱敏引用；不可为空。
     * @throws ZeroException 策略拒绝或执行失败时抛出。
     */
    private SanitizedValue sanitizeValue(
            final String fieldPath,
            final String value,
            final Map<RedactionLocation, String> trustedReferences,
            final boolean message) {
        SensitiveFieldAction action = evaluateAction(fieldPath, value);
        if (action == SensitiveFieldAction.REJECT) {
            throw LogRedactionSupport.rejected("sensitive log content was rejected", null);
        }
        String sanitized = value;
        if (action == SensitiveFieldAction.REDACT) {
            String trustedReference = null;
            if (!trustedReferences.isEmpty()) {
                RedactionLocation location = message
                        ? MESSAGE_LOCATION
                        : RedactionLocation.forField(fieldPath);
                trustedReference = trustedReferences.get(location);
            }
            sanitized = value.equals(trustedReference) ? value : redact(fieldPath, value);
        }
        return new SanitizedValue(LogTextEscaper.escapeControls(sanitized), action == SensitiveFieldAction.REDACT);
    }

    /**
     * 合并默认策略和只能收紧的附加策略。
     *
     * @param fieldPath 字段路径；不可为空。
     * @param value 字段值；不可为空。
     * @return 最终动作；不可为空。
     * @throws ZeroException 策略异常或返回空值时抛出。
     */
    private SensitiveFieldAction evaluateAction(final String fieldPath, final String value) {
        SensitiveFieldAction baseline;
        try {
            baseline = java.util.Objects.requireNonNull(
                    baselinePolicy.actionFor(fieldPath, value), "baselineAction");
        } catch (RuntimeException exception) {
            throw policyFailure(exception);
        }
        if (baseline == SensitiveFieldAction.REJECT) {
            return baseline;
        }
        try {
            SensitiveFieldAction additional = java.util.Objects.requireNonNull(
                    additionalPolicy.actionFor(fieldPath, value), "additionalAction");
            return baseline.ordinal() >= additional.ordinal() ? baseline : additional;
        } catch (RuntimeException exception) {
            throw policyFailure(exception);
        }
    }

    /**
     * 创建不携带策略异常原始 message、cause 或 suppressed 的安全失败。
     *
     * @param exception 策略异常；不可为空。
     * @return 绑定敏感字段错误码且仅保留异常类型和框架转换位置的安全异常；不可为空。
     */
    private ZeroException policyFailure(final RuntimeException exception) {
        IllegalStateException safeCause = new IllegalStateException(
                "sensitive field policy threw " + exception.getClass().getName());
        return LogRedactionSupport.rejected("sensitive field policy failed", safeCause);
    }

    /**
     * 延迟创建引用表并记录本轮确由安全门生成的脱敏引用。
     *
     * @param references 可变内部引用表；首次引用前为空。
     * @param location 消息或字段位置；不可为空。
     * @param value 安全引用；不可为空。
     * @return 含当前引用的可变内部 Map；不可为空、无序且非线程安全。
     */
    private Map<RedactionLocation, String> addReference(
            final Map<RedactionLocation, String> references,
            final RedactionLocation location,
            final String value) {
        Map<RedactionLocation, String> target = references;
        if (target == null) {
            target = new HashMap<>();
        }
        target.put(location, value);
        return target;
    }

    /**
     * 调用脱敏器并校验其输出。
     *
     * @param domain 稳定字段域；不可为空。
     * @param value 原始标识；不可为空。
     * @return 安全引用；不可为空。
     * @throws ZeroException 脱敏器失败或结果非法时抛出。
     */
    private String redact(final String domain, final String value) {
        try {
            return LogRedactionSupport.requireReference(redactor.redact(domain, value));
        } catch (RuntimeException exception) {
            if (exception instanceof ZeroException zeroException
                    && zeroException.errorCode() == LogErrorCode.SENSITIVE_FIELD_REJECTED) {
                throw zeroException;
            }
            throw LogRedactionSupport.rejected("log identifier redaction failed", exception);
        }
    }

    /**
     * 单次安全门结果和仅在当前管线调用栈内流转的可信脱敏 provenance。
     *
     * @param record 已清洗日志记录；不可为空。
     * @param redactionReferences 本次门确认的引用；不可变、可能为空且线程安全。
     */
    record SanitizationResult(
            ZeroLogRecord record,
            Map<RedactionLocation, String> redactionReferences) {
    }

    /**
     * 脱敏引用所在位置，避免消息与同名扩展字段发生 provenance 碰撞。
     *
     * @param message 是否为主消息。
     * @param fieldPath 扩展字段路径；主消息时为固定空串。
     */
    private record RedactionLocation(boolean message, String fieldPath) {

        private static RedactionLocation forField(final String fieldPath) {
            return new RedactionLocation(false, fieldPath);
        }
    }

    /**
     * 单值清洗结果。
     *
     * @param value 安全值；不可为空。
     * @param redacted 是否经过或匹配本次管线可信脱敏。
     */
    private record SanitizedValue(String value, boolean redacted) {
    }
}
