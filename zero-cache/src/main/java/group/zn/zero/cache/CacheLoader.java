package group.zn.zero.cache;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;

/**
 * 缓存自动加载器。
 *
 * @param <K> 缓存键类型。
 * @param <V> 缓存值类型。
 * @author zn
 */
@FunctionalInterface
public interface CacheLoader<K, V> {

    /**
     * 加载缓存值。
     *
     * @param key 缓存键；不可为空。
     * @return 缓存值；为空表示可写入负缓存；线程安全性由实现声明。
     */
    CompletionStage<Optional<V>> load(K key);

    /**
     * 创建同步加载器。
     *
     * @param loader 同步加载函数；不可为空。
     * @param <K> 缓存键类型。
     * @param <V> 缓存值类型。
     * @return 缓存加载器；不可为空；线程安全性由函数实现决定。
     */
    static <K, V> CacheLoader<K, V> sync(final Function<K, Optional<V>> loader) {
        Function<K, Optional<V>> current = Objects.requireNonNull(loader, "loader");
        return key -> CompletableFuture.completedFuture(current.apply(key));
    }
}
