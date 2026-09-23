package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** 多线程统计在静止后精确；不把观察值当作容量/版本 CAS。 @author zn */
class CacheStatisticsConcurrencyTest {
    @Test void completedOperationsAreCountedExactlyAfterQuiescence() throws Exception {
        var cache = new InMemoryCacheService<String, String>();
        cache.put("key", "value").toCompletableFuture().join();
        var workers = Executors.newFixedThreadPool(8);
        try {
            var tasks = new ArrayList<java.util.concurrent.Future<?>>();
            for (int i = 0; i < 8; i++) tasks.add(workers.submit(() -> {
                for (int j = 0; j < 10_000; j++) {
                    cache.get("key").toCompletableFuture().join();
                    cache.get("missing").toCompletableFuture().join();
                }
            }));
            for (var task : tasks) task.get(10, TimeUnit.SECONDS);
            assertEquals(80_000, cache.statistics().hitCount());
            assertEquals(80_000, cache.statistics().missCount());
            assertEquals(1, cache.statistics().putCount());
        } finally { workers.shutdownNow(); }
    }
}
