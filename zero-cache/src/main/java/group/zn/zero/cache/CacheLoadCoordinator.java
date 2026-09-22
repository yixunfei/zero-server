package group.zn.zero.cache;

import group.zn.zero.core.error.ZeroException;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.function.Function;

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
     * 单次加载最长等待时间。
     */
    private final Duration loadTimeout;

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
        this(maxConcurrentLoads, CachePolicy.DEFAULT_LOAD_TIMEOUT);
    }

    /**
     * 创建有界单飞协调器；不创建线程池，使用 JDK 的 future 超时调度。
     * @param maxConcurrentLoads 最大并发加载数；必须为正数。
     * @param loadTimeout 单次加载等待上限；必须为正数。
     * @throws IllegalArgumentException 当容量或超时不合法时抛出。
     */
    public CacheLoadCoordinator(final int maxConcurrentLoads, final Duration loadTimeout) {
        if (maxConcurrentLoads <= 0) {
            throw new IllegalArgumentException("maxConcurrentLoads must be positive");
        }
        this.loadTimeout = Objects.requireNonNull(loadTimeout, "loadTimeout");
        if (loadTimeout.isNegative() || loadTimeout.isZero()) {
            throw new IllegalArgumentException("loadTimeout must be positive");
        }
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
        return getOrLoad(key, loader, CompletableFuture::completedFuture);
    }

    /**
     * 单飞加载后仅由未超时的胜出结果执行一次回填；超时或取消后的迟到结果不回填。
     * @param key 缓存键；不可为空。
     * @param loader 加载逻辑；不可为空。
     * @param onLoaded 回填逻辑；不可为空；应快速返回异步结果。
     * @return 共享加载结果；不可为空；线程安全；取消会结束该 key 的本次单飞。
     * @throws ZeroException 当并发容量耗尽时抛出。
     */
    public CompletionStage<Optional<V>> getOrLoad(final K key,
            final Supplier<CompletionStage<Optional<V>>> loader,
            final Function<Optional<V>, CompletionStage<Optional<V>>> onLoaded) {
        K currentKey = Objects.requireNonNull(key, "key");
        Objects.requireNonNull(loader, "loader");
        Objects.requireNonNull(onLoaded, "onLoaded");
        CompletableFuture<Optional<V>> existing = loading.get(currentKey);
        if (existing != null) {
            return existing;
        }
        if (!loadPermits.tryAcquire()) {
            existing = loading.get(currentKey);
            if (existing != null) {
                return existing;
            }
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
        AtomicBoolean permitReleased = new AtomicBoolean();
        CompletableFuture<Optional<V>> boundedLoad = new CompletableFuture<>();
        created.whenComplete((ignored, ignoredFailure) -> {
            if (created.isCancelled()) {
                loading.remove(currentKey, created);
                releasePermit(permitReleased);
                boundedLoad.cancel(false);
            }
        });
        boundedLoad.orTimeout(loadTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .thenCompose(value -> created.isCancelled()
                        ? CompletableFuture.<Optional<V>>failedFuture(new java.util.concurrent.CancellationException())
                        : onLoaded.apply(Objects.requireNonNull(value, "loader value")))
                .orTimeout(loadTimeout.toNanos(), TimeUnit.NANOSECONDS)
                .whenComplete((value, failure) -> {
                    loading.remove(currentKey, created);
                    releasePermit(permitReleased);
                    if (failure == null) {
                        created.complete(value);
                    } else {
                        created.completeExceptionally(ZeroException.of(
                                CacheErrorCode.LOADER_FAILED, "cache loader failed for key=" + currentKey, failure));
                    }
                });
        try {
            loader.get().whenComplete((value, throwable) -> {
                if (throwable != null) {
                    boundedLoad.completeExceptionally(throwable);
                } else {
                    boundedLoad.complete(value);
                }
            });
        } catch (RuntimeException ex) {
            boundedLoad.completeExceptionally(ex);
        }
        return created;
    }

    private void releasePermit(final AtomicBoolean permitReleased) {
        if (permitReleased.compareAndSet(false, true)) {
            loadPermits.release();
        }
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
