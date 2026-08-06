package group.zn.zero.logic.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * 默认业务会话属性容器。
 *
 * @author zn
 */
public final class DefaultLogicSessionAttributes implements LogicSessionAttributes {

    /**
     * 属性表。
     */
    private final Map<LogicSessionAttributeKey<?>, Object> values = new ConcurrentHashMap<>();

    /**
     * 读取属性。
     *
     * @param key 属性键；不可为空。
     * @param <T> 属性类型。
     * @return 属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public <T> Optional<T> get(final LogicSessionAttributeKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = values.get(key);
        return value == null ? Optional.empty() : Optional.of(key.cast(value));
    }

    /**
     * 写入属性。
     *
     * @param key 属性键；不可为空。
     * @param value 属性值；不可为空。
     * @param <T> 属性类型。
     * @return 原属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public <T> Optional<T> put(final LogicSessionAttributeKey<T> key, final T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        Object previous = values.put(key, key.cast(value));
        return previous == null ? Optional.empty() : Optional.of(key.cast(previous));
    }

    /**
     * 移除属性。
     *
     * @param key 属性键；不可为空。
     * @param <T> 属性类型。
     * @return 原属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public <T> Optional<T> remove(final LogicSessionAttributeKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object previous = values.remove(key);
        return previous == null ? Optional.empty() : Optional.of(key.cast(previous));
    }

    /**
     * 原子更新属性。
     *
     * @param key 属性键；不可为空。
     * @param updater 更新函数；不可为空；入参可能为空。
     * @param <T> 属性类型。
     * @return 更新后的属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public <T> Optional<T> update(final LogicSessionAttributeKey<T> key, final Function<T, T> updater) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(updater, "updater");
        Object updated = values.compute(key, (ignored, value) -> {
            T current = value == null ? null : key.cast(value);
            T next = updater.apply(current);
            return next == null ? null : key.cast(next);
        });
        return updated == null ? Optional.empty() : Optional.of(key.cast(updated));
    }

    /**
     * 返回属性快照。
     *
     * @return 不可变、可能为空、线程安全的属性快照。
     */
    @Override
    public Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        values.forEach((key, value) -> snapshot.put(key.name(), value));
        return Map.copyOf(snapshot);
    }
}
