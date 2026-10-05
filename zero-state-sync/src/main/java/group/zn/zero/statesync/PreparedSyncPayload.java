package group.zn.zero.statesync;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** 深不可变 payload 与其摘要；仅由框架准备入口创建，禁止信任外部传入的缓存摘要。 @author zn */
final class PreparedSyncPayload extends AbstractMap<String, Object> {
    /** 已快照的不可变嵌套数据。 */
    private final Map<String, Object> values;
    /** 与 values 完全对应的摘要。 */
    private final String hash;

    private PreparedSyncPayload(final Map<String, Object> values) {
        this.values = values;
        this.hash = SyncPayloadHash.compute(values);
    }

    /** 同一 prepared payload 可直接复用，不重复快照或哈希。 */
    static PreparedSyncPayload prepare(final Map<String, Object> payload) {
        Objects.requireNonNull(payload, "payload");
        if (payload instanceof PreparedSyncPayload prepared) { return prepared; }
        Map<String, Object> snapshot = new LinkedHashMap<>();
        payload.forEach((key, value) -> snapshot.put(Objects.requireNonNull(key, "key"),
                freeze(Objects.requireNonNull(value, "value"))));
        return new PreparedSyncPayload(Collections.unmodifiableMap(snapshot));
    }

    /** 返回准备时计算的 SHA-256。 */
    String hash() { return hash; }

    /** 返回不可变、保持输入顺序、可为空的 entry 集合；深快照可并发读取。 */
    @Override public Set<Entry<String, Object>> entrySet() { return values.entrySet(); }

    /** 保留底层 Map 的查找成本，不使用 AbstractMap 的逐项扫描。 */
    @Override public Object get(final Object key) { return values.get(key); }

    /** 查询键存在性；只读且线程安全。 */
    @Override public boolean containsKey(final Object key) { return values.containsKey(key); }

    /** 返回深快照的顶层字段数量。 */
    @Override public int size() { return values.size(); }

    /** 仅接受可证明不可变的叶子，避免嵌套可变对象改变内容但复用旧摘要。 */
    private static Object freeze(final Object value) {
        if (value == null || immutableLeaf(value)) { return value; }
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            map.forEach((key, entry) -> {
                if (key != null && !immutableLeaf(key)) {
                    throw new IllegalArgumentException("prepared payload requires immutable map keys");
                }
                copy.put(key, freeze(entry));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof Collection<?> collection) {
            ArrayList<Object> copy = new ArrayList<>(collection.size());
            for (Object entry : collection) { copy.add(freeze(entry)); }
            return Collections.unmodifiableList(copy);
        }
        throw new IllegalArgumentException("prepared payload requires maps, collections and immutable scalar values");
    }

    private static boolean immutableLeaf(final Object value) {
        Class<?> type = value.getClass();
        return type == String.class || type == Boolean.class || type == Character.class
                || type == Byte.class || type == Short.class || type == Integer.class || type == Long.class
                || type == Float.class || type == Double.class || type == BigInteger.class
                || type == BigDecimal.class || type == UUID.class;
    }
}
