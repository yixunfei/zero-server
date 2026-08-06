package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 本地内存缓存服务。
 *
 * <p>支持自动加载、版本号、失效、防击穿和负缓存防穿透。该实现用于本地原型和测试，
 * 不承担生产级分布式一致性职责。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public class InMemoryCacheService<K, V> implements CacheService<K, V> {

    /**
     * 缓存条目表。
     */
    private final ConcurrentMap<K, CacheEntry<V>> entries = new ConcurrentHashMap<>();

    /**
     * 单飞加载表。
     */
    private final ConcurrentMap<K, CompletableFuture<Optional<V>>> loading = new ConcurrentHashMap<>();

    /**
     * 缓存策略。
     */
    private final CachePolicy policy;

    /**
     * 命中次数。
     */
    private final AtomicLong hitCount = new AtomicLong();

    /**
     * 未命中次数。
     */
    private final AtomicLong missCount = new AtomicLong();

    /**
     * 加载次数。
     */
    private final AtomicLong loadCount = new AtomicLong();

    /**
     * 加载失败次数。
     */
    private final AtomicLong loadFailureCount = new AtomicLong();

    /**
     * 写入次数。
     */
    private final AtomicLong putCount = new AtomicLong();

    /**
     * 失效次数。
     */
    private final AtomicLong invalidateCount = new AtomicLong();

    /**
     * 创建默认内存缓存服务。
     */
    public InMemoryCacheService() {
        this(CachePolicy.defaults());
    }

    /**
     * 创建内存缓存服务。
     *
     * @param ttl 正常缓存有效期；不可为空。
     * @param negativeTtl 负缓存有效期；不可为空。
     * @throws NullPointerException 当有效期为空时抛出。
     */
    public InMemoryCacheService(final Duration ttl, final Duration negativeTtl) {
        this(CachePolicy.of(
                Objects.requireNonNull(ttl, "ttl"),
                Objects.requireNonNull(negativeTtl, "negativeTtl")));
    }

    /**
     * 创建内存缓存服务。
     *
     * @param policy 缓存策略；不可为空。
     * @throws NullPointerException 当策略为空时抛出。
     */
    public InMemoryCacheService(final CachePolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
    }

    /**
     * 读取缓存值。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存值；为空表示未命中或负缓存命中；线程安全。
     */
    @Override
    public CompletionStage<Optional<V>> get(final K key) {
        CacheEntry<V> entry = validEntry(key);
        if (entry == null) {
            missCount.incrementAndGet();
            return CompletableFuture.completedFuture(Optional.empty());
        }
        hitCount.incrementAndGet();
        return CompletableFuture.completedFuture(entry.optionalValue());
    }

    /**
     * 写入缓存值。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @return 写入完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> put(final K key, final V value) {
        return putVersioned(key, value, -1L);
    }

    /**
     * 失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> invalidate(final K key) {
        Objects.requireNonNull(key, "key");
        entries.remove(key);
        invalidateCount.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 自动加载缓存。
     *
     * @param key 缓存键；不可为空。
     * @param loader 加载器；不可为空。
     * @return 缓存值；为空表示加载器确认不存在；线程安全。
     */
    public CompletionStage<Optional<V>> getOrLoad(final K key, final CacheLoader<K, V> loader) {
        Objects.requireNonNull(loader, "loader");
        CacheEntry<V> entry = validEntry(key);
        if (entry != null) {
            hitCount.incrementAndGet();
            return CompletableFuture.completedFuture(entry.optionalValue());
        }
        missCount.incrementAndGet();
        CompletableFuture<Optional<V>> future = loading.computeIfAbsent(Objects.requireNonNull(key, "key"), ignored -> {
            CompletableFuture<Optional<V>> loadFuture = new CompletableFuture<>();
            try {
                loader.load(key).whenComplete((loaded, throwable) -> {
                    try {
                        if (throwable != null) {
                            completeLoadFailure(loadFuture, key, throwable);
                            return;
                        }
                        Optional<V> value = Objects.requireNonNull(loaded, "loaded");
                        storeLoadedValue(key, value);
                        loadCount.incrementAndGet();
                        loadFuture.complete(value);
                    } catch (RuntimeException ex) {
                        completeLoadFailure(loadFuture, key, ex);
                    } finally {
                        loading.remove(key);
                    }
                });
            } catch (RuntimeException ex) {
                completeLoadFailure(loadFuture, key, ex);
                loading.remove(key);
            }
            return loadFuture;
        });
        return future;
    }

    /**
     * 返回缓存条目版本号。
     *
     * @param key 缓存键；不可为空。
     * @return 版本号；为空表示不存在或已过期；线程安全。
     */
    public Optional<Long> versionOf(final K key) {
        CacheEntry<V> entry = validEntry(key);
        return entry == null ? Optional.empty() : Optional.of(entry.version());
    }

    /**
     * 返回统计快照。
     *
     * @return 统计快照；不可为空；线程安全。
     */
    public CacheStatistics statistics() {
        return new CacheStatistics(
                hitCount.get(),
                missCount.get(),
                loadCount.get(),
                loadFailureCount.get(),
                putCount.get(),
                invalidateCount.get());
    }

    /**
     * 返回缓存条目快照。
     *
     * @return 不可变、无序、可能为空、线程安全的缓存条目快照。
     */
    public Map<K, CacheEntry<V>> snapshot() {
        return Map.copyOf(entries);
    }

    /**
     * 返回缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存条目；为空表示不存在或已过期；线程安全。
     */
    public Optional<CacheEntry<V>> entryOf(final K key) {
        return Optional.ofNullable(validEntry(key));
    }

    /**
     * 按指定版本写入缓存值。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @param version 指定版本；小于 0 表示自动递增。
     * @return 写入完成信号；不可为空；线程安全。
     */
    public CompletionStage<Void> putVersioned(final K key, final V value, final long version) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        entries.compute(key, (ignored, previous) -> new CacheEntry<>(
                value,
                version < 0L ? nextVersion(previous) : version,
                Instant.now().plus(effectiveTtl(policy.ttl())),
                false));
        evictIfNeeded();
        putCount.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 写入负缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 写入完成信号；不可为空；线程安全。
     */
    public CompletionStage<Void> putNegative(final K key) {
        Objects.requireNonNull(key, "key");
        entries.compute(key, (ignored, previous) -> new CacheEntry<>(
                null,
                nextVersion(previous),
                Instant.now().plus(effectiveTtl(policy.negativeTtl())),
                true));
        evictIfNeeded();
        putCount.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    /**
     * 写入指定缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @param entry 缓存条目；不可为空。
     * @return 写入完成信号；不可为空；线程安全。
     */
    public CompletionStage<Void> putEntry(final K key, final CacheEntry<V> entry) {
        entries.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(entry, "entry"));
        evictIfNeeded();
        putCount.incrementAndGet();
        return CompletableFuture.completedFuture(null);
    }

    private CacheEntry<V> validEntry(final K key) {
        Objects.requireNonNull(key, "key");
        CacheEntry<V> entry = entries.get(key);
        if (entry == null) {
            return null;
        }
        if (entry.expired(Instant.now())) {
            entries.remove(key, entry);
            return null;
        }
        return entry;
    }

    private void storeLoadedValue(final K key, final Optional<V> value) {
        entries.compute(key, (ignored, previous) -> value
                .map(current -> new CacheEntry<>(
                        current,
                        previous == null ? 1L : previous.version() + 1L,
                        Instant.now().plus(effectiveTtl(policy.ttl())),
                        false))
                .orElseGet(() -> new CacheEntry<>(
                        null,
                        previous == null ? 1L : previous.version() + 1L,
                        Instant.now().plus(effectiveTtl(policy.negativeTtl())),
                        true)));
        evictIfNeeded();
    }

    private void completeLoadFailure(final CompletableFuture<Optional<V>> loadFuture, final K key, final Throwable throwable) {
        loadFailureCount.incrementAndGet();
        loadFuture.completeExceptionally(ZeroException.of(
                CacheErrorCode.LOADER_FAILED,
                "cache loader failed for key=" + key,
                throwable));
    }

    private long nextVersion(final CacheEntry<V> previous) {
        return previous == null ? 1L : previous.version() + 1L;
    }

    private Duration effectiveTtl(final Duration baseTtl) {
        Duration jitter = policy.ttlJitter();
        if (jitter.isZero()) {
            return baseTtl;
        }
        long jitterMillis = jitter.toMillis();
        if (jitterMillis <= 0L) {
            return baseTtl;
        }
        long extraMillis = ThreadLocalRandom.current().nextLong(jitterMillis + 1L);
        return baseTtl.plusMillis(extraMillis);
    }

    private void evictIfNeeded() {
        while (entries.size() > policy.maxLocalEntries()) {
            K oldest = entries.keySet().stream().findFirst().orElse(null);
            if (oldest == null) {
                return;
            }
            entries.remove(oldest);
        }
    }
}
