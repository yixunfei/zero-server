package group.zn.zero.codegen.generator;

/**
 * 生成源码用的缩进字符串构建器。
 *
 * @author zn
 */
public final class SourceBuilder {

    /**
     * 换行符。
     */
    private static final String LINE_SEPARATOR = System.lineSeparator();

    /**
     * 源码内容。
     */
    private final StringBuilder builder = new StringBuilder(2048);

    /**
     * 当前缩进层级。
     */
    private int indent;

    /**
     * 添加一行源码。
     *
     * @param line 源码行；不可为空。
     * @return 当前构建器；不可为空；线程不安全。
     */
    public SourceBuilder line(final String line) {
        for (int index = 0; index < indent; index++) {
            builder.append("    ");
        }
        builder.append(line).append(LINE_SEPARATOR);
        return this;
    }

    /**
     * 添加空行。
     *
     * @return 当前构建器；不可为空；线程不安全。
     */
    public SourceBuilder blankLine() {
        builder.append(LINE_SEPARATOR);
        return this;
    }

    /**
     * 进入下一层缩进。
     *
     * @return 当前构建器；不可为空；线程不安全。
     */
    public SourceBuilder indent() {
        indent++;
        return this;
    }

    /**
     * 退出一层缩进。
     *
     * @return 当前构建器；不可为空；线程不安全。
     */
    public SourceBuilder outdent() {
        if (indent == 0) {
            throw new IllegalStateException("source indent is already zero");
        }
        indent--;
        return this;
    }

    /**
     * 返回源码内容。
     *
     * @return 源码内容；不可为空；有序；线程不安全。
     */
    @Override
    public String toString() {
        return builder.toString();
    }
}
