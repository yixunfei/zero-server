package group.zn.zero.gm;

import group.zn.zero.log.LogLevel;
import group.zn.zero.log.LogOperation;
import group.zn.zero.log.LogResult;
import group.zn.zero.log.LogSource;
import group.zn.zero.log.LogType;
import group.zn.zero.log.ZeroLogRecord;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * 把安全 GM 审计事件转换为统一 {@link ZeroLogRecord} 的工厂。
 *
 * <p>本工厂只读取已经安全化的事件字段，不访问执行上下文、原始请求或 handler 返回数据；
 * 生成记录后仍必须通过 {@link group.zn.zero.log.LogAppender} 进入标准安全管线。</p>
 *
 * @author zn
 */
public final class GmAuditRecordFactory {

    /** GM 审计日志来源。 */
    private final LogSource source;

    /**
     * 创建 GM 审计记录工厂。
     *
     * @param source service、instance 与 module 来源；不可为空。
     * @throws NullPointerException 当来源为空时抛出。
     */
    public GmAuditRecordFactory(final LogSource source) {
        this.source = Objects.requireNonNull(source, "source");
    }

    /**
     * 创建统一 GM 审计日志记录。
     *
     * <p>本方法不落地日志、不修改事件；返回记录的 fields 有序、不可变、可能为空且线程安全。</p>
     *
     * @param event 已安全化的 GM 审计事件；不可为空。
     * @return 统一审计日志记录；不可为空；不可变且线程安全。
     * @throws NullPointerException 当事件为空时抛出。
     * @throws RuntimeException 当事件无法满足统一日志字段契约时由 zero-log 向上抛出。
     */
    public ZeroLogRecord create(final GmAuditEvent event) {
        GmAuditEvent checkedEvent = Objects.requireNonNull(event, "event");
        return ZeroLogRecord.create(
                checkedEvent.time(),
                level(checkedEvent.result()),
                LogType.AUDIT,
                source,
                new LogOperation(operationName(checkedEvent.phase()), checkedEvent.result(), checkedEvent.errorCode()),
                checkedEvent.traceId(),
                checkedEvent.safeMessage(),
                fields(checkedEvent));
    }

    private Map<String, String> fields(final GmAuditEvent event) {
        Map<String, String> fields = new LinkedHashMap<>();
        GmAuditAttribution attribution = event.attribution();
        fields.put("phase", event.phase().name());
        fields.put("operatorRef", attribution.operatorRef());
        fields.put("sourceAddressRef", attribution.sourceAddressRef());
        fields.put("approvalRequired", Boolean.toString(attribution.approvalRequired()));
        fields.put("approvalState", attribution.approvalState().name());
        putIfPresent(fields, "approvalRef", attribution.approvalRef());
        fields.put("commandKey", event.commandKey());
        fields.put("parameterNames", String.join(",", event.parameterNames()));
        fields.put("parameterCount", Integer.toString(event.parameterCount()));
        fields.put("targetType", event.targetType());
        putIfPresent(fields, "targetRef", event.targetRef());
        fields.put("dryRun", Boolean.toString(event.dryRun()));
        fields.put("businessCommitState", event.businessCommitState().name());
        fields.put("structureFingerprint", event.structureFingerprint());
        putIfPresent(fields, "requestFingerprint", event.requestFingerprint());
        return fields;
    }

    private void putIfPresent(final Map<String, String> fields, final String key, final String value) {
        if (!value.isEmpty()) {
            fields.put(key, value);
        }
    }

    private LogLevel level(final LogResult result) {
        return switch (result) {
            case FAILURE, TIMEOUT, DEGRADED -> LogLevel.ERROR;
            case REJECTED -> LogLevel.WARN;
            case STARTED, SUCCESS -> LogLevel.INFO;
        };
    }

    private String operationName(final GmAuditPhase phase) {
        return "gm.audit." + phase.name().toLowerCase(Locale.ROOT);
    }
}
