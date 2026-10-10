package group.zn.zero.monitor;

/** Prometheus 文本转义；HELP 和 label 使用同一换行规则，避免注入额外序列。 @author zn */
final class MetricTextEscaper {
    private MetricTextEscaper() { }

    /** 写入 HELP 或 label 文本；不产生真实换行，不修改输入；调用方独占 builder。 */
    static void append(final StringBuilder builder, final String value, final boolean label) {
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\n' || current == '\r') {
                builder.append("\\n");
            } else {
                if (current == '\\' || label && current == '"') builder.append('\\');
                builder.append(current);
            }
        }
    }
}
