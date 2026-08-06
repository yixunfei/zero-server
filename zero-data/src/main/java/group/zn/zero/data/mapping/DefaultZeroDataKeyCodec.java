package group.zn.zero.data.mapping;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 默认数据键编码器。
 *
 * <p>该实现只支持 String、UUID 和常见整数键，并在编码结果中写入类型前缀。
 * 复杂组合键必须提供业务侧稳定 codec。
 *
 * @author zn
 */
public final class DefaultZeroDataKeyCodec implements ZeroDataKeyCodec<Object> {

    /**
     * 字符串前缀。
     */
    private static final String STRING_PREFIX = "str";

    /**
     * UUID 前缀。
     */
    private static final String UUID_PREFIX = "uuid";

    /**
     * long 前缀。
     */
    private static final String LONG_PREFIX = "long";

    /**
     * int 前缀。
     */
    private static final String INT_PREFIX = "int";

    /**
     * short 前缀。
     */
    private static final String SHORT_PREFIX = "short";

    /**
     * byte 前缀。
     */
    private static final String BYTE_PREFIX = "byte";

    /**
     * 创建默认数据键编码器。
     */
    public DefaultZeroDataKeyCodec() {
    }

    /**
     * 编码数据键。
     *
     * @param key 数据键；不可为空。
     * @return 编码后的键；不可为空；线程安全。
     * @throws NullPointerException 当数据键为空时抛出。
     * @throws ZeroException 当 key 类型不受默认 codec 支持时抛出。
     */
    @Override
    public String encode(final Object key) {
        Object current = Objects.requireNonNull(key, "key");
        if (current instanceof String value) {
            return STRING_PREFIX + ":" + Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(value.getBytes(StandardCharsets.UTF_8));
        }
        if (current instanceof UUID value) {
            return UUID_PREFIX + ":" + value;
        }
        if (current instanceof Long value) {
            return LONG_PREFIX + ":" + value;
        }
        if (current instanceof Integer value) {
            return INT_PREFIX + ":" + value;
        }
        if (current instanceof Short value) {
            return SHORT_PREFIX + ":" + value;
        }
        if (current instanceof Byte value) {
            return BYTE_PREFIX + ":" + value;
        }
        throw invalid("unsupported default data key type: " + current.getClass().getName(), null);
    }

    /**
     * 解码数据键。
     *
     * @param value 编码值；不可为空。
     * @return 解码后的键；不可为空；线程安全。
     * @throws NullPointerException 当编码值为空时抛出。
     * @throws ZeroException 当编码值非法时抛出。
     */
    @Override
    public Object decode(final String value) {
        String current = Objects.requireNonNull(value, "value");
        int separator = current.indexOf(':');
        if (separator <= 0) {
            throw invalid("invalid encoded data key: " + current, null);
        }
        String type = current.substring(0, separator);
        String body = current.substring(separator + 1);
        try {
            return switch (type) {
                case STRING_PREFIX -> new String(Base64.getUrlDecoder().decode(body), StandardCharsets.UTF_8);
                case UUID_PREFIX -> UUID.fromString(body);
                case LONG_PREFIX -> Long.valueOf(body);
                case INT_PREFIX -> Integer.valueOf(body);
                case SHORT_PREFIX -> Short.valueOf(body);
                case BYTE_PREFIX -> Byte.valueOf(body);
                default -> throw invalid("unsupported encoded data key type: " + type, null);
            };
        } catch (IllegalArgumentException ex) {
            throw invalid("invalid encoded data key: " + current, ex);
        }
    }

    private ZeroException invalid(final String message, final Throwable cause) {
        return ZeroException.of(DataErrorCode.MAPPING_INVALID, message, cause);
    }
}
