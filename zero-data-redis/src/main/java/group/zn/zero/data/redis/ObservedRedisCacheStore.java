package group.zn.zero.data.redis;

import group.zn.zero.cache.CacheStore;
import group.zn.zero.cache.CacheStoreEntry;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.Supplier;

/** Observes completion of each Redis cache operation, including asynchronous driver failure. */
public final class ObservedRedisCacheStore<K, V> implements CacheStore<K, V> {
    /** Runtime-owned admission and failure boundary. */
    @FunctionalInterface
    public interface Observer {
        /** Observes one asynchronous operation without replaying it. */
        <T> CompletionStage<T> observe(boolean write, Supplier<CompletionStage<T>> operation);
    }

    private final CacheStore<K, V> delegate;
    private final Observer observer;

    /** Wraps an existing cache store without taking ownership of its client. */
    public ObservedRedisCacheStore(final CacheStore<K, V> delegate, final Observer observer) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.observer = Objects.requireNonNull(observer, "observer");
    }

    @Override public CompletionStage<Optional<CacheStoreEntry<V>>> get(final K key) {
        return observer.observe(false, () -> delegate.get(key));
    }

    @Override public CompletionStage<Void> put(final K key, final CacheStoreEntry<V> entry) {
        return observer.observe(true, () -> delegate.put(key, entry));
    }

    @Override public CompletionStage<Boolean> putIfVersion(final K key, final CacheStoreEntry<V> entry) {
        return observer.observe(true, () -> delegate.putIfVersion(key, entry));
    }

    @Override public CompletionStage<Void> invalidate(final K key) {
        return observer.observe(true, () -> delegate.invalidate(key));
    }

    @Override public CompletionStage<Boolean> invalidateIfVersion(final K key, final long entityVersion) {
        return observer.observe(true, () -> delegate.invalidateIfVersion(key, entityVersion));
    }
}
