package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 缓存同步完成与写入交错的回归测试。 @author zn */
class CacheReportAuditTest {
    /** 同步成功不能永久占据单飞表。 */
    @Test void synchronousLoadCanReloadAfterInvalidation() {
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>();
        AtomicInteger calls = new AtomicInteger();
        CacheLoader<String, String> loader = CacheLoader.sync(key -> Optional.of("v" + calls.incrementAndGet()));
        assertEquals(Optional.of("v1"), cache.getOrLoad("k", loader).toCompletableFuture().join());
        cache.invalidate("k");
        assertEquals(Optional.of("v2"), cache.getOrLoad("k", loader).toCompletableFuture().join());
        assertEquals(2, calls.get());
    }
    /** 同步异常后必须允许重新加载。 */
    @Test void synchronousFailureDoesNotPoisonFutureLoads() {
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>();
        assertThrows(CompletionException.class, () -> cache.getOrLoad("k", key -> {
            throw new IllegalStateException("failed");
        }).toCompletableFuture().join());
        assertEquals(Optional.of("ok"), cache.getOrLoad("k", CacheLoader.sync(key -> Optional.of("ok")))
                .toCompletableFuture().join());
    }
    /** 加载中的显式写入不得被迟到的加载结果覆盖。 */
    @Test void explicitWriteWinsOverEarlierLoad() {
        InMemoryCacheService<String, String> cache = new InMemoryCacheService<>();
        CompletableFuture<Optional<String>> loaded = new CompletableFuture<>();
        var result = cache.getOrLoad("k", key -> loaded);
        cache.put("k", "new");
        loaded.complete(Optional.of("old"));
        result.toCompletableFuture().join();
        assertEquals(Optional.of("new"), cache.get("k").toCompletableFuture().join());
    }
    /** 协调器接到非法 null 结果时要结束 future 并归还配额。 */
    @Test void coordinatorNullResultFailsAndReleasesPermit() {
        CacheLoadCoordinator<String, String> coordinator = new CacheLoadCoordinator<>(1);
        var result = coordinator.getOrLoad("k", () -> CompletableFuture.completedFuture(null)).toCompletableFuture();
        assertEquals(true, result.isCompletedExceptionally());
        assertThrows(CompletionException.class, result::join);
        assertEquals(0, coordinator.pendingCount());
        assertEquals(Optional.of("ok"), coordinator.getOrLoad("k", () -> CompletableFuture.completedFuture(Optional.of("ok")))
                .toCompletableFuture().join());
    }

}
