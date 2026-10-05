package group.zn.zero.player;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.cache.CacheErrorCode;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.repository.CrudRepository;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 仓库 miss singleflight 必须覆盖物理回填，并隔离取消、失败和关闭。 @author zn */
class PlayerRepositorySingleFlightTest {
    /** 相同 UID 同时 miss 只读一次仓库；回填完成前的后续 miss 继续共享。 */
    @Test void concurrentMissesShareRepositoryReadUntilCacheWriteCompletes() {
        var repository = new RepositoryProbe();
        var cache = new DelayedWriteCache();
        try (var service = service(repository, cache)) {
            var first = load(service, 7);
            var second = load(service, 7);
            assertEquals(1, repository.reads.size());
            first.cancel(false);
            PlayerProfile profile = new PlayerProfile(7, 2, "current", true);
            repository.reads.getFirst().complete(Optional.of(profile));
            assertEquals(1, cache.writes);
            assertFalse(second.isDone());
            var third = load(service, 7);
            assertEquals(1, repository.reads.size());
            cache.release.complete(null);
            assertEquals(profile, second.join());
            assertEquals(profile, third.join());
            assertEquals(profile, cache.get(7L).toCompletableFuture().join().orElseThrow());

            cache.invalidate(7L).toCompletableFuture().join();
            var next = load(service, 7);
            assertEquals(2, repository.reads.size());
            PlayerProfile fresh = new PlayerProfile(7, 3, "fresh", true);
            repository.reads.getLast().complete(Optional.of(fresh));
            assertEquals(fresh, next.join());
            assertEquals(fresh, cache.get(7L).toCompletableFuture().join().orElseThrow());
        }
    }

    /** 物理失败结束同批观察者并允许下一次读取重试。 */
    @Test void failedReadReleasesTheUidForRetry() {
        var repository = new RepositoryProbe();
        try (var service = service(repository, new InMemoryCacheService<>())) {
            var first = load(service, 7);
            var second = load(service, 7);
            repository.reads.getFirst().completeExceptionally(new IllegalStateException("backend failed"));
            assertFailure(first);
            assertFailure(second);
            var retried = load(service, 7);
            assertEquals(2, repository.reads.size());
            repository.reads.getLast().complete(Optional.of(new PlayerProfile(7, "retry", true)));
            assertEquals("retry", retried.join().name());
        }
    }

    /** 关闭释放目录并结束等待者；迟到后端完成不会复活被关闭的服务。 */
    @Test void closeCompletesWaitersAndRejectsFurtherMisses() {
        var repository = new RepositoryProbe();
        try (var service = service(repository, new InMemoryCacheService<>())) {
            var first = load(service, 7);
            var second = load(service, 7);
            service.close();
            assertFailure(first);
            assertFailure(second);
            assertFailure(load(service, 8));
            assertEquals(1, repository.reads.size());
            repository.reads.getFirst().complete(Optional.of(new PlayerProfile(7, "late", true)));
            assertFailure(first);
        }
    }

    /** 不同 UID 在途加载有固定上限；满时同 UID 仍可共享，并可在物理完成后恢复。 */
    @Test void distinctMissesAreBoundedWithoutEvictingActiveLoads() {
        var repository = new RepositoryProbe();
        try (var service = service(repository, new InMemoryCacheService<>())) {
            for (int uid = 1; uid <= CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS; uid++) load(service, uid);
            var same = load(service, 1);
            var rejected = load(service, CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS + 1L);
            assertTrue(rejected.isDone());
            var failure = assertThrows(CompletionException.class, rejected::join);
            assertEquals(CacheErrorCode.BACKPRESSURE_REJECTED, ((ZeroException) failure.getCause()).errorCode());
            assertEquals(CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS, repository.reads.size());
            repository.reads.getFirst().complete(Optional.of(new PlayerProfile(1, "one", true)));
            assertEquals(1, same.join().uid());
            assertFalse(load(service, CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS + 1L).isDone());
            assertEquals(CachePolicy.DEFAULT_MAX_CONCURRENT_LOADS + 1, repository.reads.size());
        }
    }

    private static LocalPlayerService service(final RepositoryProbe repository,
            final CacheService<Long, PlayerProfile> cache) {
        return new LocalPlayerService(new LocalActorScheduler(), request -> 7, repository.backend(), cache, Runnable::run);
    }

    private static CompletableFuture<PlayerProfile> load(final LocalPlayerService service, final long uid) {
        return service.loadPlayer(new PlayerLoadRequest(uid, "trace")).toCompletableFuture();
    }

    private static void assertFailure(final CompletableFuture<?> result) {
        assertTrue(result.isDone());
        assertThrows(CompletionException.class, result::join);
    }

    /** 只允许受控 findById 的测试仓库，未完成读取不会创建真实 IO。 */
    private static final class RepositoryProbe {
        /** 调用顺序中的物理读取。 */
        private final List<CompletableFuture<Optional<PlayerProfile>>> reads = new ArrayList<>();

        @SuppressWarnings("unchecked")
        private CrudRepository<Long, PlayerProfile> backend() {
            return (CrudRepository<Long, PlayerProfile>) Proxy.newProxyInstance(CrudRepository.class.getClassLoader(),
                    new Class<?>[] {CrudRepository.class}, (proxy, method, args) -> {
                        if (!method.getName().equals("findById")) throw new UnsupportedOperationException(method.getName());
                        var result = new CompletableFuture<Optional<PlayerProfile>>();
                        reads.add(result);
                        return result;
                    });
        }
    }

    /** 物理写入在受控信号前不发布缓存值，模拟异步远端回填。 */
    private static final class DelayedWriteCache extends InMemoryCacheService<Long, PlayerProfile> {
        /** 回填完成屏障。 */
        private final CompletableFuture<Void> release = new CompletableFuture<>();
        /** 实际 put 调用次数。 */
        private int writes;

        /** @return 物理回填信号；仅测试线程使用，释放屏障后写入本地缓存。 */
        @Override public CompletionStage<Void> put(final Long key, final PlayerProfile profile) {
            writes++;
            return release.thenCompose(ignored -> super.put(key, profile));
        }
    }
}
