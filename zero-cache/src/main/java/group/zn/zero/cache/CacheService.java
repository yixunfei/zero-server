package group.zn.zero.cache;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * 缓存服务抽象。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
public interface CacheService<K, V> {

    /**
     * 读取缓存值。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存值；为空表示未命中；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 读取失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Optional<V>> get(K key);

    /**
     * 写入缓存值。
     *
     * @param key 缓存键；不可为空。
     * @param value 缓存值；不可为空。
     * @return 写入完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 写入失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> put(K key, V value);

    /**
     * 失效缓存。
     *
     * @param key 缓存键；不可为空。
     * @return 失效完成信号；不可为空；线程安全性由实现声明。
     * @throws group.zn.zero.core.error.ZeroException 失效失败时抛出，必须绑定 ErrorCode。
     */
    CompletionStage<Void> invalidate(K key);
}

