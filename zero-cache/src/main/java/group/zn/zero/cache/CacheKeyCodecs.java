package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.RecordComponent;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

/**
 * 缓存 key 编码器工厂。
 *
 * <p>默认格式为 `k1:&lt;type&gt;:&lt;payload&gt;`。字符串、枚举类名、枚举值和
 * record 字段名均使用 base64url without padding 编码，避免分隔符歧义。
 *
 * @author zn
 */
public final class CacheKeyCodecs {

    /**
     * key 格式版本。
     */
    public static final int KEY_FORMAT_VERSION = 1;

    /**
     * key 格式前缀。
     */
    public static final String KEY_FORMAT_PREFIX = "k1";

    /**
     * base64url 编码器。
     */
    private static final Base64.Encoder BASE64_URL = Base64.getUrlEncoder().withoutPadding();

    /**
     * 通用默认 key codec。
     */
    private static final CacheKeyCodec<Object> DEFAULT_CODEC = new DefaultCacheKeyCodec();

    private CacheKeyCodecs() {
    }

    /**
     * 返回默认 key codec。
     *
     * <p>支持 `String`、`Integer`、`Long`、`UUID`、`Enum` 和 Java record。
     * 不支持的普通对象会抛出异常，避免使用不稳定的默认 `toString()`。
     *
     * @param <K> 缓存 key 类型。
     * @return 默认 key codec；不可为空；线程安全。
     */
    @SuppressWarnings("unchecked")
    public static <K> CacheKeyCodec<K> defaults() {
        return (CacheKeyCodec<K>) DEFAULT_CODEC;
    }

    /**
     * 返回字符串 key codec。
     *
     * @return 字符串 key codec；不可为空；线程安全。
     */
    public static CacheKeyCodec<String> strings() {
        return new TypedCacheKeyCodec<>("string-key", String.class);
    }

    /**
     * 返回 long key codec。
     *
     * @return long key codec；不可为空；线程安全。
     */
    public static CacheKeyCodec<Long> longs() {
        return new TypedCacheKeyCodec<>("long-key", Long.class);
    }

    /**
     * 返回 UUID key codec。
     *
     * @return UUID key codec；不可为空；线程安全。
     */
    public static CacheKeyCodec<UUID> uuids() {
        return new TypedCacheKeyCodec<>("uuid-key", UUID.class);
    }

    /**
     * 编码任意默认支持的 key。
     *
     * @param key 缓存 key；不可为空。
     * @return 规范化 key 字符串；不可为空；线程安全。
     */
    public static String encodeDefault(final Object key) {
        Object current = Objects.requireNonNull(key, "key");
        if (current instanceof String text) {
            return scalar("str", encodeText(text));
        }
        if (current instanceof Integer number) {
            return scalar("i32", number.toString());
        }
        if (current instanceof Long number) {
            return scalar("i64", number.toString());
        }
        if (current instanceof UUID uuid) {
            return scalar("uuid", uuid.toString());
        }
        if (current instanceof Enum<?> enumValue) {
            return encodeEnum(enumValue);
        }
        Class<?> keyClass = current.getClass();
        if (keyClass.isRecord()) {
            return encodeRecord(current, keyClass);
        }
        throw ZeroException.of(
                CacheErrorCode.INVALID_KEY,
                "unsupported cache key type: " + keyClass.getName(),
                null);
    }

    private static String encodeEnum(final Enum<?> enumValue) {
        return scalar(
                "enum",
                encodeText(enumValue.getDeclaringClass().getName()) + ":" + encodeText(enumValue.name()));
    }

    private static String encodeRecord(final Object key, final Class<?> keyClass) {
        StringBuilder builder = new StringBuilder(128)
                .append(KEY_FORMAT_PREFIX)
                .append(":record:")
                .append(encodeText(keyClass.getName()));
        RecordComponent[] components = keyClass.getRecordComponents();
        for (RecordComponent component : components) {
            Object value = readRecordValue(key, component);
            builder.append(':')
                    .append(encodeText(component.getName()))
                    .append('=')
                    .append(value == null ? "null" : encodeDefault(value));
        }
        return builder.toString();
    }

    private static Object readRecordValue(final Object key, final RecordComponent component) {
        try {
            Method accessor = component.getAccessor();
            if (!accessor.canAccess(key)) {
                accessor.setAccessible(true);
            }
            return accessor.invoke(key);
        } catch (IllegalAccessException | InvocationTargetException ex) {
            throw ZeroException.of(
                    CacheErrorCode.INVALID_KEY,
                    "cache record key component read failed: " + component.getName(),
                    ex);
        }
    }

    private static String scalar(final String type, final String payload) {
        return KEY_FORMAT_PREFIX + ":" + type + ":" + payload;
    }

    private static String encodeText(final String value) {
        return BASE64_URL.encodeToString(Objects.requireNonNull(value, "value").getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 默认 key codec 实现。
     *
     * @author zn
     */
    private static final class DefaultCacheKeyCodec implements CacheKeyCodec<Object> {

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空。
         */
        @Override
        public String name() {
            return "zero-cache-key-default";
        }

        /**
         * 返回格式版本。
         *
         * @return 格式版本。
         */
        @Override
        public int version() {
            return KEY_FORMAT_VERSION;
        }

        /**
         * 编码 key。
         *
         * @param key 缓存 key；不可为空。
         * @return 编码结果；不可为空。
         */
        @Override
        public String encode(final Object key) {
            return encodeDefault(key);
        }
    }

    /**
     * 指定类型 key codec。
     *
     * @param <K> 缓存 key 类型。
     * @author zn
     */
    private static final class TypedCacheKeyCodec<K> implements CacheKeyCodec<K> {

        /**
         * codec 名称。
         */
        private final String name;

        /**
         * key 类型。
         */
        private final Class<K> keyType;

        /**
         * 创建指定类型 key codec。
         *
         * @param name codec 名称；不可为空。
         * @param keyType key 类型；不可为空。
         */
        private TypedCacheKeyCodec(final String name, final Class<K> keyType) {
            this.name = Objects.requireNonNull(name, "name");
            this.keyType = Objects.requireNonNull(keyType, "keyType");
        }

        /**
         * 返回 codec 名称。
         *
         * @return codec 名称；不可为空。
         */
        @Override
        public String name() {
            return name;
        }

        /**
         * 返回格式版本。
         *
         * @return 格式版本。
         */
        @Override
        public int version() {
            return KEY_FORMAT_VERSION;
        }

        /**
         * 编码 key。
         *
         * @param key 缓存 key；不可为空。
         * @return 编码结果；不可为空。
         */
        @Override
        public String encode(final K key) {
            return encodeDefault(keyType.cast(Objects.requireNonNull(key, "key")));
        }
    }
}
