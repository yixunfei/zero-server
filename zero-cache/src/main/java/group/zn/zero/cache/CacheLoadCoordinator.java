package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * 缓存加载协调器。
 *
 * <p>该协调器按 key 做 singleflight，避免热点 key 同时触发多次 loader。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public final class CacheLoadCoordinator<K, V> {

    /**
     * 最大并发加载数。
     */
    private final int maxConcurrentLoads;

    /**
     * 加载容量许可。
     */
    private final Semaphore loadPermits;

    /**
     * 正在加载的 key。
     */
    private final ConcurrentMap<K, CompletableFuture<Optional<V>>> loading = new ConcurrentHashMap<>();

    /**
     * 拒绝次数。
     */
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * 创建缓存加载协调器。
     *
     * @param maxConcurrentLoads 最大并发加载数；必须大于 0。
     * @throws IllegalArgumentException 当并发数非法时抛出。
     */
    public CacheLoadCoordinator(final int maxConcurrentLoads) {
        if (maxConcurrentLoads <= 0) {
            throw new IllegalArgumentException("maxConcurrentLoads must be positive");
        }
        this.maxConcurrentLoads = maxConcurrentLoads;
        this.loadPermits = new Semaphore(maxConcurrentLoads);
    }

    /**
     * 获取加载结果。
     *
     * @param key 缓存键；不可为空。
     * @param loader 加载逻辑；不可为空。
     * @return 加载结果；不可为空；线程安全。
     * @throws ZeroException 当并发加载数超过限制时抛出，绑定 `CacheErrorCode.BACKPRESSURE_REJECTED`。
     */
    public CompletionStage<Optional<V>> getOrLoad(
            final K key,
            final Supplier<CompletionStage<Optional<V>>> loader) {
        K currentKey = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        CompletableFuture<Optional<V>> existing = loading.get(currentKey);
        if (existing != null) {
            return existing;
        }
        if (!loadPermits.tryAcquire()) {
            rejectedCount.incrementAndGet();
            throw ZeroException.of(
                    CacheErrorCode.BACKPRESSURE_REJECTED,
                    "cache loader rejected by maxConcurrentLoads",
                    null);
        }
        CompletableFuture<Optional<V>> created = new CompletableFuture<>();
        CompletableFuture<Optional<V>> previous = loading.putIfAbsent(currentKey, created);
        if (previous != null) {
            loadPermits.release();
            return previous;
        }
        try {
            loader.get().whenComplete((value, throwable) -> {
                Throwable failure = throwable;
                if (failure == null && value == null) {
                    failure = new NullPointerException("loader value must not be null");
                }
                loading.remove(currentKey, created);
                loadPermits.release();
                if (failure != null) created.completeExceptionally(failure);
                else created.complete(value);
            });
        } catch (RuntimeException ex) {
            loading.remove(currentKey, created);
            loadPermits.release();
            created.completeExceptionally(ex);
        }
        return created;
    }

    /**
     * 返回正在加载的 key 数量。
     *
     * @return 正在加载的 key 数量；线程安全。
     */
    public int pendingCount() {
        return loading.size();
    }

    /**
     * 返回拒绝次数。
     *
     * @return 拒绝次数；线程安全。
     */
    public long rejectedCount() {
        return rejectedCount.get();
    }
}
