package group.zn.zero.cache;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 二级缓存存储抽象。
 *
 * <p>该接口用于 Redis、Memcached 或其他分布式缓存 Adapter 接入。接口不暴露任何具体驱动类型。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public interface CacheStore<K, V> {

    /**
     * 读取二级缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存条目；为空表示未命中；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 读取失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Optional<CacheStoreEntry<V>>> get(K key);

    /**
     * 写入二级缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @param entry 缓存条目；不可为空。
     * @return 写入完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 写入失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> put(K key, CacheStoreEntry<V> entry);

    /**
     * 按实体版本条件写入二级缓存条目。
     *
     * @param key 缓存键；不可为空。
     * @param entry 缓存条目；不可为空。
     * @return true 表示写入成功；false 表示后端已有更新版本；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 写入失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Boolean> putIfVersion(K key, CacheStoreEntry<V> entry);

    /**
     * 失效二级缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 失效失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> invalidate(K key);

    /**
     * 按实体版本条件失效二级缓存。
     *
     * @param key 缓存键；不可为空。
     * @param entityVersion 请求方已知实体版本；必须大于等于 0。
     * @return true 表示失效成功或无需失效；false 表示后端已有更新版本；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 失效失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Boolean> invalidateIfVersion(K key, long entityVersion);
}
