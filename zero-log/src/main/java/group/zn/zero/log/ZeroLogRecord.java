package group.zn.zero.log;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Objects;
import java.util.TreeMap;

/**
 * 首版统一、不可变的结构化日志记录。
 *
 * <p>记录把来源和操作值对象扁平暴露为固定字段；构造时字段 Map 按 key 排序并仅防御性复制一次。
 * 记录自身不可变且线程安全，但其中的敏感内容只有经 {@link LogAppender} 后才可交给终端 sink。
 *
 * @author zn
 */
public final class ZeroLogRecord {

    /**
     * 首版字段契约版本。
     */
    public static final String SCHEMA_VERSION = "1";

    /**
     * 最大扩展字段数量。
     */
    public static final int MAX_FIELDS = 32;

    /**
     * 最大字段名字符数。
     */
    public static final int MAX_FIELD_KEY_LENGTH = 64;

    /**
     * 最大单字段值字符数。
     */
    public static final int MAX_FIELD_VALUE_LENGTH = 4096;

    /**
     * 最大消息字符数。
     */
    public static final int MAX_MESSAGE_LENGTH = 4096;

    /**
     * 消息和字段名值合计最大字符数。
     */
    public static final int MAX_CONTENT_LENGTH = 16384;

    /**
     * 固定标识最大字符数。
     */
    public static final int MAX_IDENTIFIER_LENGTH = 128;

    /**
     * 日志时间。
     */
    private final Instant time;

    /**
     * 日志严重等级。
     */
    private final LogLevel level;

    /**
     * 日志业务分类。
     */
    private final LogType logType;

    /**
     * 日志来源。
     */
    private final LogSource source;

    /**
     * 日志操作与结果。
     */
    private final LogOperation logOperation;

    /**
     * 显式链路追踪标识。
     */
    private final String traceId;

    /**
     * 日志说明。
     */
    private final String message;

    /**
     * 按 key 排序的不可变字段。
     */
    private final Map<String, String> fields;

    /**
     * 创建已规范化记录。
     *
     * @param time 日志时间；不可为空。
     * @param level 日志等级；不可为空。
     * @param logType 日志类型；不可为空。
     * @param source 日志来源；不可为空。
     * @param logOperation 操作与结果；不可为空。
     * @param traceId 已规范化 TraceId；不可为空。
     * @param message 日志说明；不可为空。
     * @param fields 已冻结有序字段；不可为空。
     */
    private ZeroLogRecord(
            final Instant time,
            final LogLevel level,
            final LogType logType,
            final LogSource source,
            final LogOperation logOperation,
            final String traceId,
            final String message,
            final Map<String, String> fields) {
        this.time = time;
        this.level = level;
        this.logType = logType;
        this.source = source;
        this.logOperation = logOperation;
        this.traceId = traceId;
        this.message = message;
        this.fields = fields;
    }

    /**
     * 创建并校验统一日志记录。
     *
     * <p>字段 Map 会按 key 排序并仅复制一次；后续修改调用方 Map 不影响记录。该方法不执行敏感
     * 字段清洗、不读取线程上下文、不生成 TraceId、不执行 IO，也不修改调用方数据。
     *
     * @param time 日志时间；不可为空。
     * @param level 日志等级；不可为空。
     * @param logType 日志类型；不可为空。
     * @param source 日志来源；不可为空。
     * @param operation 操作与结果；不可为空。
     * @param traceId 显式 TraceId；trim 后须非空，最长 128 字符且无控制字符。
     * @param message 日志说明；不可为空，最长 4096 字符；控制字符由安全管线转义。
     * @param fields 扩展字段；可为空，按 key 有序；返回视图不可变、可能为空且线程安全。
     * @return 不可变、线程安全的日志记录；不可为空。
     * @throws group.zn.zero.core.error.ZeroException 固定字段、预算或 ErrorCode 组合非法时抛出，
     *         并绑定 {@link LogErrorCode#INVALID_RECORD}。
     */
    public static ZeroLogRecord create(
            final Instant time,
            final LogLevel level,
            final LogType logType,
            final LogSource source,
            final LogOperation operation,
            final String traceId,
            final String message,
            final Map<String, String> fields) {
        Map<String, String> immutableFields = copyFields(fields);
        ZeroLogRecord record = new ZeroLogRecord(
                time,
                level,
                logType,
                source,
                operation,
                LogRecordValidator.normalizeIdentifier(traceId, "traceId"),
                message,
                immutableFields);
        LogRecordValidator.requireValid(record);
        return record;
    }

    /**
     * 返回字段契约版本。
     *
     * @return 固定字符串 {@value #SCHEMA_VERSION}；线程安全。
     */
    public String schemaVersion() {
        return SCHEMA_VERSION;
    }

    /**
     * 返回日志时间。
     *
     * @return 时间；不可为空；线程安全。
     */
    public Instant time() {
        return time;
    }

    /**
     * 返回日志严重等级。
     *
     * @return 日志等级；不可为空；线程安全。
     */
    public LogLevel level() {
        return level;
    }

    /**
     * 返回日志业务分类。
     *
     * @return 日志类型；不可为空；线程安全。
     */
    public LogType logType() {
        return logType;
    }

    /**
     * 返回日志来源值对象。
     *
     * @return 不可变日志来源；不可为空；线程安全。
     */
    public LogSource source() {
        return source;
    }

    /**
     * 返回服务名。
     *
     * @return 服务名；不可为空；线程安全。
     */
    public String serviceName() {
        return source.serviceName();
    }

    /**
     * 返回实例标识。
     *
     * @return 实例标识；不可为空；线程安全。
     */
    public String instanceId() {
        return source.instanceId();
    }

    /**
     * 返回模块名。
     *
     * @return 模块名；不可为空；线程安全。
     */
    public String module() {
        return source.module();
    }

    /**
     * 返回操作与结果值对象。
     *
     * @return 不可变操作值对象；不可为空；线程安全。
     */
    public LogOperation logOperation() {
        return logOperation;
    }

    /**
     * 返回稳定操作名。
     *
     * @return 操作名；不可为空；线程安全。
     */
    public String operation() {
        return logOperation.name();
    }

    /**
     * 返回操作结果。
     *
     * @return 操作结果；不可为空；线程安全。
     */
    public LogResult result() {
        return logOperation.result();
    }

    /**
     * 返回显式 TraceId。
     *
     * @return TraceId；不可为空；线程安全。
     */
    public String traceId() {
        return traceId;
    }

    /**
     * 返回可选错误码。
     *
     * @return 错误码；开始和成功时为空；线程安全。
     */
    public ErrorCode errorCode() {
        return logOperation.errorCode();
    }

    /**
     * 返回日志说明。
     *
     * @return 日志说明；不可为空；线程安全。
     */
    public String message() {
        return message;
    }

    /**
     * 返回有序不可变字段。
     *
     * @return 按 key 自然序有序、不可变、可能为空且线程安全的 Map；不可为空。
     */
    public Map<String, String> fields() {
        return fields;
    }

    /**
     * 添加或替换单个字段并返回新记录。
     *
     * <p>没有值变化时返回当前记录；其他情况只复制一次字段 Map，不修改当前记录。
     *
     * @param key 字段名；不可为空，长度 1～64 且不得包含控制字符。
     * @param value 字段值；不可为空，最长 4096 字符。
     * @return 当前记录或新的不可变记录；不可为空；字段仍按 key 有序。
     * @throws group.zn.zero.core.error.ZeroException 新字段使记录违反预算时抛出。
     */
    public ZeroLogRecord withField(final String key, final String value) {
        if (key == null
                || value == null
                || key.isEmpty()
                || key.length() > MAX_FIELD_KEY_LENGTH
                || value.length() > MAX_FIELD_VALUE_LENGTH
                || LogTextEscaper.containsControl(key)
                || !fields.containsKey(key) && fields.size() >= MAX_FIELDS) {
            throw ZeroException.of(LogErrorCode.INVALID_RECORD);
        }
        if (value.equals(fields.get(key))) {
            return this;
        }
        NavigableMap<String, String> updated = new TreeMap<>(fields);
        updated.put(key, value);
        ZeroLogRecord record = new ZeroLogRecord(
                time, level, logType, source, logOperation, traceId, message, freezeOwned(updated));
        LogRecordValidator.requireValid(record);
        return record;
    }

    /**
     * 使用安全门拥有的消息和字段创建记录。
     *
     * @param sanitizedMessage 已清洗消息；不可为空。
     * @param ownedFields 安全门唯一持有的有序字段；为空表示复用当前字段。
     * @return 当前记录或清洗后的新记录；不可为空。
     */
    ZeroLogRecord withSanitizedContent(
            final String sanitizedMessage,
            final NavigableMap<String, String> ownedFields) {
        if (sanitizedMessage == message && ownedFields == null) {
            return this;
        }
        Map<String, String> sanitizedFields = ownedFields == null ? fields : freezeOwned(ownedFields);
        ZeroLogRecord record = new ZeroLogRecord(
                time, level, logType, source, logOperation, traceId, sanitizedMessage, sanitizedFields);
        LogRecordValidator.requireValid(record);
        return record;
    }

    /**
     * 复制并冻结调用方字段。
     *
     * @param sourceFields 调用方字段；可为空。
     * @return 有序不可变字段；不可为空。
     */
    private static Map<String, String> copyFields(final Map<String, String> sourceFields) {
        if (sourceFields == null || sourceFields.isEmpty()) {
            return Map.of();
        }
        if (sourceFields.size() > MAX_FIELDS) {
            throw ZeroException.of(LogErrorCode.INVALID_RECORD);
        }
        NavigableMap<String, String> copied = new TreeMap<>();
        for (Map.Entry<String, String> entry : sourceFields.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (key == null
                    || value == null
                    || key.isEmpty()
                    || key.length() > MAX_FIELD_KEY_LENGTH
                    || value.length() > MAX_FIELD_VALUE_LENGTH
                    || LogTextEscaper.containsControl(key)) {
                throw ZeroException.of(LogErrorCode.INVALID_RECORD);
            }
            copied.put(key, value);
        }
        return freezeOwned(copied);
    }

    /**
     * 冻结当前方法唯一持有的有序字段 Map，不再执行第二次复制。
     *
     * @param ownedFields 唯一持有的有序 Map；不可为空。
     * @return 不可修改的有序视图；不可为空。
     */
    private static Map<String, String> freezeOwned(final NavigableMap<String, String> ownedFields) {
        return Collections.unmodifiableNavigableMap(ownedFields);
    }

    /**
     * 判断所有固定字段和扩展字段是否相等。
     *
     * @param other 其他对象；可为空。
     * @return 字段值全部相等时返回 true；线程安全。
     */
    @Override
    public boolean equals(final Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ZeroLogRecord that)) {
            return false;
        }
        return time.equals(that.time)
                && level == that.level
                && logType == that.logType
                && source.equals(that.source)
                && logOperation.equals(that.logOperation)
                && traceId.equals(that.traceId)
                && message.equals(that.message)
                && fields.equals(that.fields);
    }

    /**
     * 返回全部固定字段和扩展字段的哈希值。
     *
     * @return 与 {@link #equals(Object)} 一致的哈希值；线程安全。
     */
    @Override
    public int hashCode() {
        return Objects.hash(time, level, logType, source, logOperation, traceId, message, fields);
    }

    /**
     * 返回不包含消息、字段值或原始敏感内容的诊断摘要。
     *
     * @return 安全诊断摘要；不可为空；线程安全。
     */
    @Override
    public String toString() {
        return "ZeroLogRecord{"
                + "schemaVersion='" + SCHEMA_VERSION + '\''
                + ", time=" + time
                + ", level=" + level
                + ", logType=" + logType
                + ", source=" + source
                + ", operation='" + operation() + '\''
                + ", result=" + result()
                + ", errorCode=" + (errorCode() == null ? "" : errorCode().code())
                + ", messageLength=" + message.length()
                + ", fieldCount=" + fields.size()
                + '}';
    }
}
