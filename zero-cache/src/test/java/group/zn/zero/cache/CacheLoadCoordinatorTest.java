package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 缓存加载许可生命周期测试。
 *
 * @author zn
 */
class CacheLoadCoordinatorTest {

    /** 无响应 loader 超时后释放容量，迟到结果不回填，也不影响同 key 的下一次加载。 */
    @Test
    void timedOutLoadReleasesPermitWithoutLateBackfill() throws Exception {
        var coordinator = new CacheLoadCoordinator<String, String>(1, Duration.ofMillis(50));
        var never = new CompletableFuture<Optional<String>>();
        AtomicInteger backfills = new AtomicInteger();
        var first = coordinator.getOrLoad("key", () -> never, value -> {
            backfills.incrementAndGet();
            return CompletableFuture.completedFuture(value);
        }).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
        assertEquals(0, coordinator.pendingCount());
        assertEquals(Optional.of("new"), coordinator.getOrLoad("key",
                () -> CompletableFuture.completedFuture(Optional.of("new"))).toCompletableFuture().join());
        never.complete(Optional.of("old"));
        assertEquals(0, backfills.get());
        assertEquals(0, coordinator.pendingCount());
    }

    /** 回填阶段不返回也不能永久占有加载许可。 */
    @Test
    void stalledBackfillAlsoReleasesPermit() throws Exception {
        var coordinator = new CacheLoadCoordinator<String, String>(1, Duration.ofMillis(50));
        var first = coordinator.getOrLoad("key",
                () -> CompletableFuture.completedFuture(Optional.of("value")),
                value -> new CompletableFuture<>()).toCompletableFuture();
        assertThrows(ExecutionException.class, () -> first.get(2, TimeUnit.SECONDS));
        assertEquals(0, coordinator.pendingCount());
    }

    /** 取消永不完成的单飞加载后应释放并发许可。 */
    @Test
    void cancelledLoadShouldReleasePermit() {
        CacheLoadCoordinator<String, String> coordinator = new CacheLoadCoordinator<>(1);
        CompletableFuture<Optional<String>> never = new CompletableFuture<>();
        CompletableFuture<Optional<String>> load = coordinator
                .getOrLoad("first", () -> never)
                .toCompletableFuture();

        load.cancel(false);

        CompletableFuture<Optional<String>> second = coordinator
                .getOrLoad("second", () -> CompletableFuture.completedFuture(Optional.of("ok")))
                .toCompletableFuture();
        assertEquals(Optional.of("ok"), second.join());
        assertEquals(0, coordinator.pendingCount());
    }
}
