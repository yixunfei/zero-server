package group.zn.zero.cache;

import java.time.Instant;
import java.util.Optional;

/**
 * 缓存条目。
 *
 * @param value 缓存值；负缓存时为空。
 * @param version 版本号。
 * @param expiresAt 过期时间。
 * @param negative 是否为负缓存条目。
 * @param entityVersion 实体版本；0 表示未版本化。
 * @param <V> 缓存值类型。
 * @author zn
 */
public record CacheEntry<V>(V value, long version, Instant expiresAt, boolean negative, long entityVersion) {

    /**
     * 创建未版本化条目；条目不可变，可并发共享。
     * @param value 缓存值。
     * @param version 缓存版本。
     * @param expiresAt 过期时间。
     * @param negative 是否为负缓存。
     */
    public CacheEntry(final V value, final long version, final Instant expiresAt, final boolean negative) {
        this(value, version, expiresAt, negative, 0L);
    }

    /**
     * 创建缓存条目。
     *
     * @throws NullPointerException 当过期时间为空时抛出。
     */
    public CacheEntry {
        java.util.Objects.requireNonNull(expiresAt, "expiresAt");
        if (entityVersion < 0L) {
            throw new IllegalArgumentException("entityVersion must be non-negative");
        }
    }

    /**
     * 返回是否已过期。
     *
     * @param now 当前时间；不可为空。
     * @return true 表示已过期；线程安全。
     */
    public boolean expired(final Instant now) {
        return !expiresAt.isAfter(java.util.Objects.requireNonNull(now, "now"));
    }

    /**
     * 返回可选值。
     *
     * @return 可选值；负缓存时为空；线程安全。
     */
    public Optional<V> optionalValue() {
        return negative ? Optional.empty() : Optional.ofNullable(value);
    }
}
