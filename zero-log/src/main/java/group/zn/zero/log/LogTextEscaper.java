package group.zn.zero.log;

/**
 * 日志单行控制字符检测与转义工具。
 *
 * @author zn
 */
final class LogTextEscaper {

    /**
     * 十六进制大写字符表。
     */
    private static final char[] HEX = "0123456789ABCDEF".toCharArray();

    /**
     * 工具类不允许实例化。
     */
    private LogTextEscaper() {
    }

    /**
     * 判断字符串是否包含 C0、DEL 或 C1 控制字符。
     *
     * @param value 待检查字符串；不可为空。
     * @return 包含控制字符时返回 true。
     */
    static boolean containsControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (isControl(value.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把控制字符转为不会破坏单行的字面量。
     *
     * <p>没有控制字符时返回原字符串，避免热路径分配。
     *
     * @param value 待转义文本；不可为空。
     * @return 原字符串或转义后的新字符串；不可为空。
     */
    static String escapeControls(final String value) {
        int firstControl = firstControl(value);
        if (firstControl < 0) {
            return value;
        }
        StringBuilder escaped = new StringBuilder(value.length() + 16);
        escaped.append(value, 0, firstControl);
        for (int index = firstControl; index < value.length(); index++) {
            appendEscaped(escaped, value.charAt(index));
        }
        return escaped.toString();
    }

    /**
     * 返回第一个控制字符位置。
     *
     * @param value 待检查文本；不可为空。
     * @return 首个位置；不存在时返回 -1。
     */
    private static int firstControl(final String value) {
        for (int index = 0; index < value.length(); index++) {
            if (isControl(value.charAt(index))) {
                return index;
            }
        }
        return -1;
    }

    /**
     * 追加单个字符的安全表示。
     *
     * @param target 目标 builder；不可为空。
     * @param value 字符。
     */
    private static void appendEscaped(final StringBuilder target, final char value) {
        switch (value) {
            case '\r' -> target.append("\\r");
            case '\n' -> target.append("\\n");
            case '\t' -> target.append("\\t");
            default -> {
                if (isControl(value)) {
                    target.append("\\u")
                            .append(HEX[(value >>> 12) & 0xF])
                            .append(HEX[(value >>> 8) & 0xF])
                            .append(HEX[(value >>> 4) & 0xF])
                            .append(HEX[value & 0xF]);
                } else {
                    target.append(value);
                }
            }
        }
    }

    /**
     * 判断字符是否属于禁止直接输出的控制区间。
     *
     * @param value 字符。
     * @return C0、DEL 或 C1 控制字符返回 true。
     */
    private static boolean isControl(final char value) {
        return value <= 0x1F || value >= 0x7F && value <= 0x9F;
    }
}
