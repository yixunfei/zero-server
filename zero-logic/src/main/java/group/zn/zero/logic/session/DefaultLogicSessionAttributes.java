package group.zn.zero.logic.session;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import group.zn.zero.core.error.ZeroException;
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
    private final Map<String, Entry> values = new LinkedHashMap<>();
    /** 实例监视器保护关闭状态，与全部写入使用同一提交边界。 */
    private boolean closed;
    /** 禁止 updater 递归修改容器。 */
    private boolean updating;

    /**
     * 读取属性。
     *
     * @param key 属性键；不可为空。
     * @param <T> 属性类型。
     * @return 属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized <T> Optional<T> get(final LogicSessionAttributeKey<T> key) {
        Objects.requireNonNull(key, "key");
        Object value = checkedValue(key);
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
    public synchronized <T> Optional<T> put(final LogicSessionAttributeKey<T> key, final T value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        requireWritable();
        Object previous = checkedValue(key);
        values.put(key.name(), new Entry(key.valueType(), key.cast(value)));
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
    public synchronized <T> Optional<T> remove(final LogicSessionAttributeKey<T> key) {
        Objects.requireNonNull(key, "key");
        requireWritable();
        Object previous = checkedValue(key);
        values.remove(key.name());
        return previous == null ? Optional.empty() : Optional.of(key.cast(previous));
    }

    /**
     * 原子更新属性，返回 null 表示删除。updater 必须短时非阻塞，不得递归修改容器。
     *
     * @param key 属性键；不可为空。
     * @param updater 更新函数；不可为空；入参可能为空。
     * @param <T> 属性类型。
     * @return 更新后的属性值；不可为空；可能为空；线程安全。
     */
    @Override
    public synchronized <T> Optional<T> update(final LogicSessionAttributeKey<T> key, final Function<T, T> updater) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(updater, "updater");
        requireWritable();
        Object value = checkedValue(key);
        T updated;
        updating = true;
        try { updated = updater.apply(value == null ? null : key.cast(value)); }
        finally { updating = false; }
        if (updated == null) values.remove(key.name());
        else values.put(key.name(), new Entry(key.valueType(), key.cast(updated)));
        return updated == null ? Optional.empty() : Optional.of(key.cast(updated));
    }

    /**
     * 返回属性快照。
     *
     * @return 不可变、可能为空、线程安全的属性快照。
     */
    @Override
    public synchronized Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        values.forEach((name, entry) -> snapshot.put(name, entry.value()));
        return Map.copyOf(snapshot);
    }

    synchronized void close() { closed = true; }
    synchronized boolean isClosed() { return closed; }

    synchronized void markRequest() {
        requireWritable();
        // 先校验全部统计键，避免同名异型冲突造成部分统计已经提交。
        checkedValue(StandardLogicSessionAttributes.REQUEST_COUNT);
        checkedValue(StandardLogicSessionAttributes.LAST_REQUEST_TIME_MILLIS);
        checkedValue(StandardLogicSessionAttributes.LAST_REQUEST_INTERVAL_MILLIS);
        long now = System.currentTimeMillis();
        update(StandardLogicSessionAttributes.REQUEST_COUNT, count -> count == null ? 1L : count + 1L);
        put(StandardLogicSessionAttributes.LAST_REQUEST_TIME_MILLIS, now).ifPresent(previous ->
                put(StandardLogicSessionAttributes.LAST_REQUEST_INTERVAL_MILLIS, Math.max(0L, now - previous)));
    }
    private Object checkedValue(final LogicSessionAttributeKey<?> key) {
        Entry entry = values.get(key.name());
        if (entry == null) return null;
        if (!entry.type().equals(key.valueType())) throw ZeroException.of(
                LogicSessionErrorCode.ATTRIBUTE_TYPE_CONFLICT, "logic attribute type conflict: " + key.name(), null);
        return entry.value();
    }
    private void requireWritable() {
        if (closed) throw ZeroException.of(LogicSessionErrorCode.SESSION_CLOSED,
                LogicSessionErrorCode.SESSION_CLOSED.message(), null);
        if (updating) throw new IllegalStateException("recursive attribute mutation from updater");
    }
    /** 活跃属性类型和值；删除时一起释放，不保留无界类型目录。 @author zn */
    private record Entry(Class<?> type, Object value) { }
}
