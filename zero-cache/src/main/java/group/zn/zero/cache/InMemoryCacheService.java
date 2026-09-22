package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.LinkedHashSet;
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
     * 条目插入序列，用于确定最老条目。
     */
    private final LinkedHashSet<K> insertionOrder = new LinkedHashSet<>();

    /**
     * 淘汰锁，保证并发写入后容量不超过上限。
     */
    private final Object evictionLock = new Object();

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
        updateEntry(key, previous -> {
            loading.remove(key);
            return null;
        });
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
        CompletableFuture<Optional<V>> future = new CompletableFuture<>();
        CompletableFuture<Optional<V>> existing = loading.putIfAbsent(key, future);
        if (existing != null) {
            return existing;
        }
        CompletableFuture<Optional<V>> boundedLoad = new CompletableFuture<>();
        future.whenComplete((ignored, failure) -> {
            if (future.isCancelled()) {
                loading.remove(key, future);
                boundedLoad.cancel(false);
            }
        });
        try {
            boundedLoad.orTimeout(policy.loadTimeout().toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)
                    .whenComplete((loaded, throwable) -> {
                try {
                    if (throwable != null) {
                        completeLoadFailure(future, key, throwable);
                        return;
                    }
                    Optional<V> value = Objects.requireNonNull(loaded, "loaded");
                    storeLoadedValue(key, value, future);
                    loadCount.incrementAndGet();
                    loading.remove(key, future);
                    future.complete(value);
                } catch (RuntimeException ex) {
                    completeLoadFailure(future, key, ex);
                }
            });
            loader.load(key).whenComplete((loaded, failure) -> {
                if (failure == null) {
                    boundedLoad.complete(loaded);
                } else {
                    boundedLoad.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException ex) {
            boundedLoad.completeExceptionally(ex);
        }
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
        updateEntry(key, previous -> {
            loading.remove(key);
            return new CacheEntry<>(value, version < 0L ? nextVersion(previous) : version,
                    Instant.now().plus(effectiveTtl(policy.ttl())), false);
        });
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
        updateEntry(key, previous -> {
            loading.remove(key);
            return new CacheEntry<>(null, nextVersion(previous),
                    Instant.now().plus(effectiveTtl(policy.negativeTtl())), true);
        });
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
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(entry, "entry");
        updateEntry(key, previous -> {
            loading.remove(key);
            return entry;
        });
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
            synchronized (evictionLock) {
                if (entries.remove(key, entry)) {
                    insertionOrder.remove(key);
                }
            }
            return null;
        }
        return entry;
    }

    private void storeLoadedValue(final K key, final Optional<V> value,
            final CompletableFuture<Optional<V>> owner) {
        updateEntry(key, previous -> {
            if (loading.get(key) != owner || previous != null && !previous.expired(Instant.now())) {
                return previous;
            }
            return value
                .map(current -> new CacheEntry<>(
                        current,
                        previous == null ? 1L : previous.version() + 1L,
                        Instant.now().plus(effectiveTtl(policy.ttl())),
                        false))
                .orElseGet(() -> new CacheEntry<>(
                        null,
                        previous == null ? 1L : previous.version() + 1L,
                        Instant.now().plus(effectiveTtl(policy.negativeTtl())),
                        true));
        });
    }

    /**
     * 原子合并分层缓存回填，旧版本不得覆盖较新版本。调用方可并发使用。
     * @param key 缓存键；不可为空。
     * @param candidate 带原始有效期的候选条目；不可为空。
     * @return 合并后条目；不可为空；不可变。
     */
    CacheEntry<V> mergeEntry(final K key, final CacheEntry<V> candidate) {
        return updateEntry(key, previous -> {
            if (previous != null && !previous.expired(Instant.now())) {
                boolean bothVersioned = previous.entityVersion() > 0 && candidate.entityVersion() > 0;
                if (bothVersioned && previous.entityVersion() > candidate.entityVersion()
                        || (!bothVersioned || previous.entityVersion() == candidate.entityVersion())
                        && previous.version() >= candidate.version()) {
                    return previous;
                }
            }
            loading.remove(key);
            return candidate;
        });
    }

    /** 同一锁内校验条目身份与实体版本，异步 L2 操作不得删除期间更新的条目。 */
    boolean invalidateEntry(final K key, final CacheEntry<V> expected, final long entityVersion) {
        synchronized (evictionLock) {
            CacheEntry<V> current = entries.get(Objects.requireNonNull(key, "key"));
            if (current != null && current.entityVersion() > entityVersion) {
                return false;
            }
            if (current != expected) {
                return current == null;
            }
            entries.remove(key);
            insertionOrder.remove(key);
            loading.remove(key);
            invalidateCount.incrementAndGet();
            return true;
        }
    }

    private void completeLoadFailure(final CompletableFuture<Optional<V>> loadFuture, final K key, final Throwable throwable) {
        loading.remove(key, loadFuture);
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

    /** 原子维护条目和有序索引；读命中保持无锁，容量淘汰为 O(1)。 */
    private CacheEntry<V> updateEntry(final K key,
            final java.util.function.UnaryOperator<CacheEntry<V>> update) {
        synchronized (evictionLock) {
            CacheEntry<V> previous = entries.get(key);
            CacheEntry<V> next = update.apply(previous);
            if (next == null) {
                entries.remove(key);
                insertionOrder.remove(key);
            } else if (next != previous) {
                entries.put(key, next);
                insertionOrder.remove(key);
                insertionOrder.add(key);
            }
            while (entries.size() > policy.maxLocalEntries()) {
                K oldest = insertionOrder.removeFirst();
                entries.remove(oldest);
            }
            return next;
        }
    }
}
