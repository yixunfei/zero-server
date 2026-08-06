package group.zn.zero.data.redis;

import group.zn.zero.cache.CacheHealthSnapshot;
import group.zn.zero.cache.CacheLoader;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.CacheStatistics;
import group.zn.zero.cache.CacheStore;
import group.zn.zero.cache.LayeredCacheService;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Redis 分布式缓存适配服务。
 *
 * <p>该服务是 `zero-cache` 分层缓存抽象与 Redis L2 store 的门面。无 Redis store 构造时会退化为
 * 本地 L1 缓存，便于单元测试和本地原型。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public final class RedisDistributedCacheService<K, V> implements CacheService<K, V> {

    /**
     * 后端名称。
     */
    private final String backendName;

    /**
     * 分层缓存服务。
     */
    private final LayeredCacheService<K, V> delegate;

    /**
     * 创建默认 Redis 分布式缓存适配服务。
     */
    public RedisDistributedCacheService() {
        this("redis", CachePolicy.defaults(), null);
    }

    /**
     * 创建 Redis 分布式缓存适配服务。
     *
     * @param backendName 后端名称；不可为空。
     * @param ttl 正常缓存有效期；不可为空。
     * @param negativeTtl 负缓存有效期；不可为空。
     * @throws NullPointerException 当后端名称或有效期为空时抛出。
     */
    public RedisDistributedCacheService(final String backendName, final Duration ttl, final Duration negativeTtl) {
        this(backendName, CachePolicy.of(ttl, negativeTtl), null);
    }

    /**
     * 创建 Redis 分布式缓存适配服务。
     *
     * @param backendName 后端名称；不可为空。
     * @param policy 缓存策略；不可为空。
     * @param redisStore Redis 二级缓存存储；可为空。
     * @throws NullPointerException 当后端名称或策略为空时抛出。
     */
    public RedisDistributedCacheService(
            final String backendName,
            final CachePolicy policy,
            final CacheStore<K, V> redisStore) {
        this.backendName = Objects.requireNonNull(backendName, "backendName");
        this.delegate = new LayeredCacheService<>(Objects.requireNonNull(policy, "policy"), redisStore);
    }

    /**
     * 返回后端名称。
     *
     * @return 后端名称；不可为空；线程安全。
     */
    public String backendName() {
        return backendName;
    }

    /**
     * 读取缓存值。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存值；为空表示未命中或负缓存；线程安全。
     */
    @Override
    public CompletionStage<Optional<V>> get(final K key) {
        return delegate.get(key);
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
        return delegate.put(key, value);
    }

    /**
     * 按实体版本写入缓存值。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @param entityVersion 实体版本；必须大于等于 0。
     * @return 写入完成信号；不可为空；线程安全。
     */
    public CompletionStage<Void> putVersioned(final K key, final V value, final long entityVersion) {
        return delegate.putVersioned(key, value, entityVersion);
    }

    /**
     * 失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> invalidate(final K key) {
        return delegate.invalidate(key);
    }

    /**
     * 按实体版本条件失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @param entityVersion 实体版本；必须大于等于 0。
     * @return true 表示失效成功或无需失效；线程安全。
     */
    public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
        return delegate.invalidateIfVersion(key, entityVersion);
    }

    /**
     * 自动加载缓存值。
     *
     * @param key 缓存键；不可为空。
     * @param loader 加载器；不可为空。
     * @return 缓存值；为空表示加载器确认不存在；线程安全。
     */
    public CompletionStage<Optional<V>> getOrLoad(final K key, final CacheLoader<K, V> loader) {
        return delegate.getOrLoad(key, loader);
    }

    /**
     * 标记一次写回失败。
     *
     * @return 当前写回失败次数；线程安全。
     */
    public long markWriteBackFailure() {
        return delegate.markWriteBackFailure();
    }

    /**
     * 增加积压数量。
     *
     * @param amount 新增积压数量；必须大于等于 0。
     * @return 当前积压数量；线程安全。
     */
    public long addBacklog(final long amount) {
        return delegate.addBacklog(amount);
    }

    /**
     * 清理积压数量。
     *
     * @return 清理后的积压数量，固定为 0；线程安全。
     */
    public long clearBacklog() {
        return delegate.clearBacklog();
    }

    /**
     * 返回写回失败次数。
     *
     * @return 写回失败次数；线程安全。
     */
    public long writeBackFailureCount() {
        return delegate.healthSnapshot().writeBackFailureCount();
    }

    /**
     * 返回当前积压数量。
     *
     * @return 积压数量；线程安全。
     */
    public long backlogCount() {
        return delegate.healthSnapshot().backlogCount();
    }

    /**
     * 返回是否处于降级状态。
     *
     * @return true 表示降级；线程安全。
     */
    public boolean degraded() {
        return delegate.degraded();
    }

    /**
     * 返回缓存统计快照。
     *
     * @return 缓存统计快照；不可为空；线程安全。
     */
    public CacheStatistics statistics() {
        return delegate.statistics();
    }

    /**
     * 返回缓存健康状态快照。
     *
     * @return 健康状态快照；不可为空；线程安全。
     */
    public CacheHealthSnapshot healthSnapshot() {
        return delegate.healthSnapshot();
    }
}
