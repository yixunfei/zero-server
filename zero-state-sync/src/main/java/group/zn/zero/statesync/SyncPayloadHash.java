package group.zn.zero.statesync;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.Collection;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** 保持既有 canonical 格式的状态 payload SHA-256；摘要实例按线程复用。 @author zn */
final class SyncPayloadHash {
    /** MessageDigest 非线程安全，每个调用线程独占。 */
    private static final ThreadLocal<MessageDigest> DIGEST = ThreadLocal.withInitial(SyncPayloadHash::newDigest);

    private SyncPayloadHash() { }

    /** 返回内容摘要；仅运行 CPU 操作，不保留 payload 引用。 */
    static String compute(final Object payload) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, payload);
        MessageDigest digest = DIGEST.get();
        digest.reset();
        return HexFormat.of().formatHex(digest.digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    }

    private static MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required", impossible);
        }
    }

    /** 向一个 builder 递归写入，避免为每个子节点分配中间字符串与 stream。 */
    private static void append(final StringBuilder target, final Object value) {
        if (value == null) {
            target.append("null");
        } else if (value instanceof Map<?, ?> map) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            map.forEach((key, entry) -> sorted.put(String.valueOf(key), entry));
            target.append('{');
            boolean first = true;
            for (Map.Entry<String, Object> entry : sorted.entrySet()) {
                if (!first) { target.append(','); }
                first = false;
                escape(target, entry.getKey());
                target.append(':');
                append(target, entry.getValue());
            }
            target.append('}');
        } else if (value instanceof Collection<?> collection) {
            target.append('[');
            boolean first = true;
            for (Object entry : collection) {
                if (!first) { target.append(','); }
                first = false;
                append(target, entry);
            }
            target.append(']');
        } else {
            escape(target, value.getClass().isArray()
                    ? Arrays.deepToString(new Object[] {value}) : Objects.toString(value));
        }
    }

    /** 只转义反斜杠与双引号，保持已有摘要字节格式。 */
    private static void escape(final StringBuilder target, final String value) {
        target.append('"');
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' || current == '"') { target.append('\\'); }
            target.append(current);
        }
        target.append('"');
    }
}
