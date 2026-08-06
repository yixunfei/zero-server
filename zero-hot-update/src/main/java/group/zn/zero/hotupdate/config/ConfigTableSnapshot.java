package group.zn.zero.hotupdate.config;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 不可变 typed 配置表快照。
 *
 * <p>快照以 `LinkedHashMap` 复制候选数据并暴露只读视图，保证 key 查询 O(1) 且迭代顺序
 * 与 CSV 行顺序一致。发布后不会发生原地修改，可以被任意业务读取线程安全共享。
 *
 * @param <K> 配置 key 类型。
 * @param <V> 配置对象类型。
 * @author zn
 */
public final class ConfigTableSnapshot<K, V> {

    /**
     * 稳定表名。
     */
    private final String tableName;

    /**
     * 单调递增本地版本。
     */
    private final long version;

    /**
     * CSV 原始字节 SHA-256 十六进制摘要。
     */
    private final String checksum;

    /**
     * 候选加载完成时间。
     */
    private final Instant loadedAt;

    /**
     * 按 CSV 行顺序保存的配置映射。
     */
    private final Map<K, V> values;

    ConfigTableSnapshot(
            final String tableName,
            final long version,
            final String checksum,
            final Instant loadedAt,
            final Map<K, V> values) {
        this.tableName = Objects.requireNonNull(tableName, "tableName");
        if (version < 1) {
            throw new IllegalArgumentException("version must be positive");
        }
        this.version = version;
        this.checksum = Objects.requireNonNull(checksum, "checksum");
        this.loadedAt = Objects.requireNonNull(loadedAt, "loadedAt");
        this.values = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(values, "values")));
    }

    /**
     * 返回稳定表名。
     *
     * @return 表名；不可为空；线程安全。
     */
    public String tableName() {
        return tableName;
    }

    /**
     * 返回本地版本。
     *
     * @return 版本；大于 0；线程安全。
     */
    public long version() {
        return version;
    }

    /**
     * 返回 CSV 原始字节 SHA-256 摘要。
     *
     * @return 小写十六进制摘要；不可为空；线程安全。
     */
    public String checksum() {
        return checksum;
    }

    /**
     * 返回候选加载完成时间。
     *
     * @return 加载时间；不可为空；线程安全。
     */
    public Instant loadedAt() {
        return loadedAt;
    }

    /**
     * 返回配置行数量。
     *
     * @return 行数；非负；线程安全。
     */
    public int rowCount() {
        return values.size();
    }

    /**
     * 按 key 查找配置对象。
     *
     * @param key 配置 key；不可为空。
     * @return 配置对象；为空表示不存在；线程安全。
     */
    public Optional<V> find(final K key) {
        return Optional.ofNullable(values.get(Objects.requireNonNull(key, "key")));
    }

    /**
     * 返回按 CSV 行顺序保存的配置映射。
     *
     * @return 不可变、有序、可能为空、线程安全的配置映射。
     */
    public Map<K, V> values() {
        return values;
    }

    /**
     * 返回按 CSV 行顺序保存的配置对象列表。
     *
     * @return 不可变、有序、可能为空、线程安全的对象列表。
     */
    public List<V> orderedValues() {
        return List.copyOf(new ArrayList<>(values.values()));
    }
}
