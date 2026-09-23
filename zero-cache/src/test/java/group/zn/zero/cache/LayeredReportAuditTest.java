package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 受控 L2 响应交错回归。 @author zn */
class LayeredReportAuditTest {
    /** 迟到旧值不得覆盖新写入；迟到读调用也返回合并后的新值。 */
    @Test void staleBackfillDoesNotReplaceCurrentValue() {
        Store store = new Store();
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(CachePolicy.defaults(), store);
        var read = cache.get("k");
        cache.put("k", "new").toCompletableFuture().join();
        store.read.complete(Optional.of(new CacheStoreEntry<>("old", 0, 0, Instant.now().plusSeconds(10), false)));
        assertEquals(Optional.of("new"), read.toCompletableFuture().join());
        assertEquals(Optional.of("new"), cache.get("k").toCompletableFuture().join());
    }
    /** L2 已有更高缓存版本时，后续本地写入必须推进版本。 */
    @Test void observedL2VersionAdvancesLocalGenerator() {
        Store store = new Store();
        store.read.complete(Optional.of(new CacheStoreEntry<>("old", 100, 0, Instant.now().plusSeconds(10), false)));
        LayeredCacheService<String, String> cache = new LayeredCacheService<>(CachePolicy.defaults(), store);
        cache.get("k").toCompletableFuture().join();
        cache.put("k", "new").toCompletableFuture().join();
        assertEquals(101, store.written.cacheVersion());
        assertEquals(Optional.of("new"), cache.get("k").toCompletableFuture().join());
    }
    /** 可控读取与立即完成写入的存储。 */
    private static final class Store implements CacheStore<String, String> {
        /** 可控 L2 读取。 */
        private final CompletableFuture<Optional<CacheStoreEntry<String>>> read = new CompletableFuture<>();
        /** 最后一次写入。 */
        private CacheStoreEntry<String> written;
        /** @return 可控读取结果。 */
        @Override public CompletionStage<Optional<CacheStoreEntry<String>>> get(String key) { return read; }
        /** @return 写入完成信号。 */
        @Override public CompletionStage<Void> put(String key, CacheStoreEntry<String> entry) { written = entry; return CompletableFuture.completedFuture(null); }
        /** @return 条件写入完成信号。 */
        @Override public CompletionStage<Boolean> putIfVersion(String key, CacheStoreEntry<String> entry) { written = entry; return CompletableFuture.completedFuture(true); }
        /** @return 失效完成信号。 */
        @Override public CompletionStage<Void> invalidate(String key) { return CompletableFuture.completedFuture(null); }
        /** @return 条件失效完成信号。 */
        @Override public CompletionStage<Boolean> invalidateIfVersion(String key, long version) { return CompletableFuture.completedFuture(true); }
    }
}
