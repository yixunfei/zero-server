package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 分层缓存条件失效和超时加载边界回归。 @author zn */
class LayeredCacheConcurrencyTest {
    /** L2 拒绝条件失效时，已有 L1 条目继续可读。 */
    @Test
    void rejectedInvalidationPreservesLocalEntry() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.putVersioned("key", "current", 1).toCompletableFuture().join();
        var invalidation = cache.invalidateIfVersion("key", 1).toCompletableFuture();
        store.invalidation.complete(false);
        assertFalse(invalidation.join());
        assertEquals(Optional.of("current"), cache.get("key").toCompletableFuture().join());
        assertEquals(0, store.reads);
    }

    /** L2 确认期间同 key 的新写入不能被迟到的 L1 删除移除。 */
    @Test
    void delayedInvalidationPreservesConcurrentWrite() {
        Store store = new Store();
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults(), store);
        cache.putVersioned("key", "old", 1).toCompletableFuture().join();
        var invalidation = cache.invalidateIfVersion("key", 1).toCompletableFuture();
        cache.putVersioned("key", "new", 2).toCompletableFuture().join();
        store.invalidation.complete(true);
        assertFalse(invalidation.join());
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        assertEquals(0, store.reads);
    }

    /** 仅本地部署也必须检查实体版本。 */
    @Test
    void localOnlyInvalidationChecksEntityVersion() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        cache.putVersioned("key", "new", 2).toCompletableFuture().join();
        assertFalse(cache.invalidateIfVersion("key", 1).toCompletableFuture().join());
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        assertTrue(cache.invalidateIfVersion("key", 2).toCompletableFuture().join());
        assertTrue(cache.get("key").toCompletableFuture().join().isEmpty());
    }

    /** 版本 0 的显式普通写仍可替换带实体版本条目，并重置其失效版本语义。 */
    @Test
    void unversionedWriteKeepsOrdinaryPutSemantics() {
        var cache = new LayeredCacheService<String, String>(CachePolicy.defaults());
        cache.putVersioned("key", "versioned", 2).toCompletableFuture().join();
        cache.put("key", "plain").toCompletableFuture().join();
        assertEquals(Optional.of("plain"), cache.get("key").toCompletableFuture().join());
        assertTrue(cache.invalidateIfVersion("key", 0).toCompletableFuture().join());
    }

    /** 真实服务配置的 loader 超时不得被迟到完成覆盖。 */
    @Test
    void timedOutLoaderCannotBackfillEitherCache() throws Exception {
        CachePolicy policy = new CachePolicy(Duration.ofMinutes(1), Duration.ofSeconds(1),
                Duration.ZERO, 8, 1, false, Duration.ofMillis(50));
        var cache = new LayeredCacheService<String, String>(policy);
        var late = new CompletableFuture<Optional<String>>();
        var loaded = cache.getOrLoad("key", key -> late).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> loaded.get(2, TimeUnit.SECONDS));
        cache.put("key", "new").toCompletableFuture().join();
        late.complete(Optional.of("old"));
        assertEquals(Optional.of("new"), cache.get("key").toCompletableFuture().join());
        var local = new InMemoryCacheService<String, String>(policy);
        var lateLocal = new CompletableFuture<Optional<String>>();
        var localLoad = local.getOrLoad("key", key -> lateLocal).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> localLoad.get(2, TimeUnit.SECONDS));
        local.put("key", "new").toCompletableFuture().join();
        lateLocal.complete(Optional.of("old"));
        assertEquals(Optional.of("new"), local.get("key").toCompletableFuture().join());
    }

    /** 提供可控失效结果，读取计数用于证明 L1 未丢失。 */
    private static final class Store implements CacheStore<String, String> {
        /** 延迟的失效结果。 */
        private final CompletableFuture<Boolean> invalidation = new CompletableFuture<>();
        /** 后端读取次数。 */
        private int reads;
        /** 返回缺失并计数。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(final String key) {
            reads++;
            return CompletableFuture.completedFuture(Optional.empty());
        }
        /** 确认测试写入。 */
        @Override public CompletionStage<Void> put(final String key, final CacheStoreEntry<String> entry) {
            return CompletableFuture.completedFuture(null);
        }
        /** 确认测试条件写入。 */
        @Override public CompletionStage<Boolean> putIfVersion(final String key, final CacheStoreEntry<String> entry) {
            return CompletableFuture.completedFuture(true);
        }
        /** 确认无条件删除。 */
        @Override public CompletionStage<Void> invalidate(final String key) {
            return CompletableFuture.completedFuture(null);
        }
        /** 返回延迟结果。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(final String key, final long version) {
            return invalidation;
        }
    }
}
