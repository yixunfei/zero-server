package group.zn.zero.examples.business;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.InMemoryCacheService;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.repository.InMemoryCrudRepository;
import group.zn.zero.event.EventType;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/** 每日限领、权威提交和提交后失败的回归测试；全部使用本地替身。 @author zn */
class DailyRewardServiceTest {
    /** 通知发送成功但确认失败时重放同一事件 ID；消费者去重，不重复改变库存。 */
    @Test void notificationReplaySurvivesServiceRecreationAndDeduplicates() {
        var stored = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var saves = new java.util.concurrent.atomic.AtomicInteger();
        @SuppressWarnings("unchecked")
        var repository = (group.zn.zero.data.repository.CrudRepository<Long, DailyRewardInventory>)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{group.zn.zero.data.repository.CrudRepository.class}, (proxy, method, args) -> {
                            if (method.getName().equals("save") && saves.incrementAndGet() == 2) {
                                return CompletableFuture.failedFuture(new IllegalStateException("notification acknowledgement failed"));
                            }
                            return method.invoke(stored, args);
                        });
        var events = bus();
        var deliveries = new java.util.concurrent.atomic.AtomicInteger();
        var consumedIds = new java.util.HashSet<String>();
        try (var subscription = events.register(EventType.INTERNAL, event -> {
            deliveries.incrementAndGet();
            consumedIds.add(event.eventId());
            return CompletableFuture.completedFuture(null);
        })) {
            var first = new DailyRewardService(repository, new InMemoryCacheService<>(), events, TODAY);
            assertThrows(CompletionException.class, () -> claim(first, 1, "first"));
            assertTrue(stored.findById(1L).toCompletableFuture().join().orElseThrow().pendingNotification());
            var recovered = new DailyRewardService(repository, new InMemoryCacheService<>(), events, TODAY);
            assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(recovered, 1, "retry").status());
            assertTrue(recovered.replayNotification(1).toCompletableFuture().join());
            assertEquals(2, deliveries.get());
            assertEquals(1, consumedIds.size());
            assertEquals(100, stored.findById(1L).toCompletableFuture().join().orElseThrow().quantity());
            assertEquals(false, stored.findById(1L).toCompletableFuture().join().orElseThrow().pendingNotification());
        }
    }

    /** 数据库实际提交但观察结果失败时，恢复读取领取记录决定重复，不自动再次发奖。 */
    @Test void unknownWriteOutcomeIsResolvedFromStoredClaim() {
        var stored = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var injected = new java.util.concurrent.atomic.AtomicBoolean();
        @SuppressWarnings("unchecked")
        var repository = (group.zn.zero.data.repository.CrudRepository<Long, DailyRewardInventory>)
                java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{group.zn.zero.data.repository.CrudRepository.class}, (proxy, method, args) -> {
                            Object result = method.invoke(stored, args);
                            if (method.getName().equals("save") && injected.compareAndSet(false, true)) {
                                return CompletableFuture.failedFuture(new IllegalStateException("write outcome unavailable"));
                            }
                            return result;
                        });
        var service = new DailyRewardService(repository, new InMemoryCacheService<>(), bus(), TODAY);
        assertThrows(CompletionException.class, () -> claim(service, 1, "first"));
        assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(service, 1, "retry").status());
        var tomorrow = new DailyRewardService(repository, new InMemoryCacheService<>(), bus(),
                Clock.offset(TODAY, java.time.Duration.ofDays(1)));
        assertThrows(CompletionException.class, () -> claim(tomorrow, 1, "next-day"));
        assertTrue(service.replayNotification(1).toCompletableFuture().join());
        assertEquals(100, stored.findById(1L).toCompletableFuture().join().orElseThrow().quantity());
    }
    /** 可复现的 UTC 日期，测试不依赖宿主时间。 */
    private static final Clock TODAY = Clock.fixed(Instant.parse("2026-10-12T12:00:00Z"), ZoneOffset.UTC);

    /** 相同客户端键不串号；同玩家换键、换奖励不能增发。 */
    @Test
    void scopesClaimsByPlayerAndServerDay() {
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var service = service(repository, new InMemoryCacheService<>(), bus(), TODAY);
        assertEquals(DailyRewardClaimResult.Status.ACCEPTED, claim(service, 1, "shared").status());
        assertEquals(DailyRewardClaimResult.Status.ACCEPTED, claim(service, 2, "shared").status());
        assertEquals("shared", claim(service, 1, "different").idempotencyKey());
        assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(service, 1, "different").status());
        assertThrows(ZeroException.class, () -> service.claim(1, "bad", "gem", 100, "trace"));
        assertThrows(ZeroException.class, () -> service.claim(1, "bad", "coin", 999, "trace"));
        assertEquals(100, repository.findById(1L).toCompletableFuture().join().orElseThrow().quantity());
        assertEquals(100, repository.findById(2L).toCompletableFuture().join().orElseThrow().quantity());
    }

    /** UTC 跨日后才允许再次领取，沿用库存版本条件写。 */
    @Test
    void allowsNextServerDay() {
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var cache = new InMemoryCacheService<Long, DailyRewardInventory>();
        claim(service(repository, cache, bus(), TODAY), 1, "first");
        var tomorrow = Clock.offset(TODAY, java.time.Duration.ofDays(1));
        assertEquals(DailyRewardClaimResult.Status.ACCEPTED,
                claim(service(repository, cache, bus(), tomorrow), 1, "second").status());
        DailyRewardInventory stored = repository.findById(1L).toCompletableFuture().join().orElseThrow();
        assertEquals(200, stored.quantity());
        assertEquals(4, stored.version());
        assertEquals("2026-10-13", stored.claimDay());
        // 时钟回拨不能重新赋予领取资格。
        assertThrows(CompletionException.class,
                () -> claim(service(repository, cache, bus(), TODAY), 1, "rollback"));
        assertEquals(stored, repository.findById(1L).toCompletableFuture().join().orElseThrow());
    }

    /** 缓存保留旧快照时，领取资格仍取决于权威仓库。 */
    @Test
    void staleCacheDoesNotAuthorizeAnotherClaim() {
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var cache = new InMemoryCacheService<Long, DailyRewardInventory>();
        var service = service(repository, cache, bus(), TODAY);
        claim(service, 1, "first");
        cache.put(1L, DailyRewardInventory.empty(1)).toCompletableFuture().join();
        assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(service, 1, "retry").status());
        cache.invalidate(1L).toCompletableFuture().join();
        assertEquals(100, service.loadInventory(1).toCompletableFuture().join().quantity());
        assertEquals(2, cache.get(1L).toCompletableFuture().join().orElseThrow().version());
    }

    /** 提交后缓存失效失败必须可见，但重试不能再次发奖。 */
    @Test
    void cacheFailureCannotSplitClaimMarkerFromInventory() {
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var service = service(repository, new FailingInvalidationCache(), bus(), TODAY);
        assertThrows(CompletionException.class, () -> claim(service, 1, "first"));
        assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(service, 1, "retry").status());
        assertEquals(100, repository.findById(1L).toCompletableFuture().join().orElseThrow().quantity());
    }

    /** 通知失败进入死信，不撤销奖励，不把网络重试误当成再次发奖。 */
    @Test
    void eventFailureDoesNotGrantAgain() {
        var deadLetters = new InMemoryDeadLetterSink();
        var events = new InMemoryEventBus(deadLetters, TODAY, Runnable::run);
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        var service = service(repository, new InMemoryCacheService<>(), events, TODAY);
        try (var ignored = events.register(EventType.INTERNAL, event ->
                CompletableFuture.failedFuture(ZeroException.of(SystemErrorCode.SYSTEM_ERROR)))) {
            assertThrows(CompletionException.class, () -> claim(service, 1, "first"));
            assertEquals(DailyRewardClaimResult.Status.DUPLICATE, claim(service, 1, "retry").status());
            assertEquals(1, deadLetters.deadLetters().size());
            assertEquals(100, repository.findById(1L).toCompletableFuture().join().orElseThrow().quantity());
        }
    }

    /** 库存溢出在保存前失败，不改变已提交实体。 */
    @Test
    void overflowDoesNotCommit() {
        var repository = new InMemoryCrudRepository<Long, DailyRewardInventory>();
        repository.save(new DailyRewardInventory(1L, 0, "coin", Integer.MAX_VALUE, "", "", false, ""))
                .toCompletableFuture().join();
        var service = service(repository, new InMemoryCacheService<>(), bus(), TODAY);
        CompletionException failure = assertThrows(CompletionException.class, () -> claim(service, 1, "overflow"));
        assertTrue(failure.getCause() instanceof ZeroException);
        assertEquals(1, repository.findById(1L).toCompletableFuture().join().orElseThrow().version());
    }

    private static DailyRewardClaimResult claim(final DailyRewardService service, final long uid, final String key) {
        return service.claim(uid, key, "coin", 100, "trace-" + key).toCompletableFuture().join();
    }

    private static DailyRewardService service(final InMemoryCrudRepository<Long, DailyRewardInventory> repository,
            final CacheService<Long, DailyRewardInventory> cache, final InMemoryEventBus events, final Clock clock) {
        return new DailyRewardService(repository, cache, events, clock);
    }

    private static InMemoryEventBus bus() {
        return new InMemoryEventBus(new InMemoryDeadLetterSink(), TODAY, Runnable::run);
    }

    /** 模拟提交后缓存不可用；无外部资源、线程安全。 */
    private static final class FailingInvalidationCache implements CacheService<Long, DailyRewardInventory> {
        /** @return 总是未命中，无副作用；key 为查询键。 */
        @Override
        public CompletionStage<Optional<DailyRewardInventory>> get(final Long key) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        /** 接受回填但不保存；key/value 为测试输入，无副作用。 @return 完成信号。 */
        @Override
        public CompletionStage<Void> put(final Long key, final DailyRewardInventory value) {
            return CompletableFuture.completedFuture(null);
        }
        /** @param key 失效键。 @return 固定失败信号，无状态变更。 */
        @Override
        public CompletionStage<Void> invalidate(final Long key) {
            return CompletableFuture.failedFuture(ZeroException.of(SystemErrorCode.SYSTEM_ERROR));
        }
    }
}
