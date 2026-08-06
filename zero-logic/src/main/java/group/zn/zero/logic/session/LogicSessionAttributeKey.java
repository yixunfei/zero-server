package group.zn.zero.logic.session;

import java.util.Objects;

/**
 * 业务会话属性键。
 *
 * @param name 属性名。
 * @param valueType 属性类型。
 * @param <T> 属性值类型。
 * @author zn
 */
public record LogicSessionAttributeKey<T>(String name, Class<T> valueType) {

    /**
     * 创建业务会话属性键。
     *
     * @throws NullPointerException 当属性名或属性类型为空时抛出。
     * @throws IllegalArgumentException 当属性名为空字符串时抛出。
     */
    public LogicSessionAttributeKey {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(valueType, "valueType");
        if (name.isBlank()) {
            throw new IllegalArgumentException("name must not be blank");
        }
    }

    /**
     * 创建业务会话属性键。
     *
     * @param name 属性名；不可为空。
     * @param valueType 属性类型；不可为空。
     * @param <T> 属性值类型。
     * @return 属性键；不可为空；线程安全。
     */
    public static <T> LogicSessionAttributeKey<T> of(final String name, final Class<T> valueType) {
        return new LogicSessionAttributeKey<>(name, valueType);
    }

    /**
     * 检查并转换属性值。
     *
     * @param value 属性值；不可为空。
     * @return 转换后的属性值；不可为空；线程安全。
     * @throws ClassCastException 当属性值类型不匹配时抛出。
     */
    public T cast(final Object value) {
        return valueType.cast(value);
    }
}
