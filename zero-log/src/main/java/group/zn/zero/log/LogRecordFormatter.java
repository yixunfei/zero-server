package group.zn.zero.log;

import java.util.Map;
import java.util.Objects;

/**
 * 按首版固定字段顺序输出稳定单行文本的格式化工具。
 *
 * <p>当前文本仅用于本地开发和测试，不冻结为生产文件、JSON 或 Kafka 线格式。格式化器会再次
 * 转义控制字符，因此即使装配层误把未过管线的合法记录直接交给本地 sink，也不会破坏单行。
 *
 * @author zn
 */
public final class LogRecordFormatter {

    /**
     * 工具类不允许实例化。
     */
    private LogRecordFormatter() {
    }

    /**
     * 按冻结顺序格式化日志记录。
     *
     * <p>扩展字段已由记录构造阶段排序，本方法有界遍历且不执行临时排序、不使用 stream。
     *
     * @param record 日志记录；不可为空。
     * @return 单行文本；不可为空；字段顺序稳定。
     * @throws NullPointerException 当日志记录为空时抛出。
     */
    public static String formatLine(final ZeroLogRecord record) {
        ZeroLogRecord current = Objects.requireNonNull(record, "record");
        StringBuilder line = new StringBuilder(256 + current.message().length());
        line.append("schemaVersion=").append(current.schemaVersion())
                .append(" time=").append(current.time())
                .append(" level=").append(current.level())
                .append(" logType=").append(current.logType())
                .append(" serviceName=");
        appendSafe(line, current.serviceName());
        line.append(" instanceId=");
        appendSafe(line, current.instanceId());
        line.append(" module=");
        appendSafe(line, current.module());
        line.append(" operation=");
        appendSafe(line, current.operation());
        line.append(" result=").append(current.result())
                .append(" traceId=");
        appendSafe(line, current.traceId());
        line.append(" errorCode=");
        if (current.errorCode() != null) {
            appendSafe(line, current.errorCode().code());
        }
        line.append(" message=");
        appendSafe(line, current.message());
        line.append(" fields=");
        appendFields(line, current.fields());
        return line.toString();
    }

    /**
     * 追加按 key 排序的字段。
     *
     * @param target 目标 builder；不可为空。
     * @param fields 有序不可变字段；不可为空。
     */
    private static void appendFields(
            final StringBuilder target,
            final Map<String, String> fields) {
        boolean first = true;
        for (Map.Entry<String, String> entry : fields.entrySet()) {
            if (!first) {
                target.append(',');
            }
            appendSafe(target, entry.getKey());
            target.append('=');
            appendSafe(target, entry.getValue());
            first = false;
        }
    }

    /**
     * 追加不含裸控制字符的文本。
     *
     * @param target 目标 builder；不可为空。
     * @param value 文本；不可为空。
     */
    private static void appendSafe(final StringBuilder target, final String value) {
        target.append(LogTextEscaper.escapeControls(value));
    }
}
