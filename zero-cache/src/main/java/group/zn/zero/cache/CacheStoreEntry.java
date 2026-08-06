package group.zn.zero.cache;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;

/**
 * 二级缓存存储条目。
 *
 * @param value 缓存值；负缓存时为空。
 * @param cacheVersion 缓存层版本号。
 * @param entityVersion 业务实体版本号。
 * @param expiresAt 过期时间。
 * @param negative 是否负缓存。
 * @param <V> 缓存值类型。
 * @author zn
 */
public record CacheStoreEntry<V>(
        V value,
        long cacheVersion,
        long entityVersion,
        Instant expiresAt,
        boolean negative) {

    /**
     * 创建二级缓存存储条目。
     *
     * @throws NullPointerException 当过期时间为空时抛出。
     * @throws IllegalArgumentException 当版本号非法时抛出。
     */
    public CacheStoreEntry {
        if (cacheVersion < 0L) {
            throw new IllegalArgumentException("cacheVersion must be non-negative");
        }
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
        expiresAt = Objects.requireNonNull(expiresAt, "expiresAt");
        if (!negative) {
            Objects.requireNonNull(value, "value");
        }
    }

    /**
     * 返回是否已过期。
     *
     * @param now 当前时间；不可为空。
     * @return true 表示已过期；线程安全。
     */
    public boolean expired(final Instant now) {
        return !expiresAt.isAfter(Objects.requireNonNull(now, "now"));
    }

    /**
     * 返回可选缓存值。
     *
     * @return 可选值；负缓存为空；线程安全。
     */
    public Optional<V> optionalValue() {
        return negative ? Optional.empty() : Optional.of(value);
    }
}
