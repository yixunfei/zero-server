package group.zn.zero.data.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.data.error.DataErrorCode;
import group.zn.zero.data.model.VersionedEntity;
import group.zn.zero.data.repository.CrudRepository;
import java.lang.reflect.Proxy;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/** 用可控完成收据验证 flush 并发预算、跨目标推进和单键代际保留。 @author zn */
class PersistenceParallelFlushTest {
    /** 网络完成顺序由测试控制，不依赖时延断言；同时在途不得超过预算。 */
    @Test
    void independentTargetsProgressWithinTheConcurrencyBudget() {
        List<SaveCall> calls = new ArrayList<>();
        var manager = manager();
        manager.registerTarget(new PersistenceTarget<>("a", repository("a", calls)));
        manager.registerTarget(new PersistenceTarget<>("b", repository("b", calls)));
        for (int index = 0; index < 5; index++) {
            String id = "p" + index;
            manager.markDirty(index % 2 == 0 ? "a" : "b", id, DataThreadBinding.player(id),
                    () -> new Archive(id, 0));
        }
        var result = manager.flushNow().toCompletableFuture();
        assertEquals(2, calls.size());
        assertEquals(List.of("a", "b"), calls.stream().map(SaveCall::target).toList());
        assertFalse(result.isDone());
        for (int completed = 0; completed < 5; completed++) {
            assertTrue(calls.stream().filter(call -> !call.future().isDone()).count() <= 2);
            calls.get(completed).future().complete(null);
        }
        assertEquals(5, result.join().selectedCount());
        assertEquals(5, result.join().successCount());
        assertEquals(0, result.join().remainingDirtyCount());
        assertEquals(5, manager.statistics().flushAttemptCount());
        assertEquals(5, manager.statistics().flushSuccessCount());
    }

    /** 第一次保存完成不能清除新代际；排队的 flush 不得与旧代际对同一键并发。 */
    @Test
    void newGenerationWaitsForPriorFlushAndFailuresRemainDirty() {
        List<SaveCall> calls = new ArrayList<>();
        var manager = manager();
        manager.registerTarget(new PersistenceTarget<>("a", repository("a", calls)));
        manager.registerTarget(new PersistenceTarget<>("b", repository("b", calls)));
        manager.markDirty("a", "p", DataThreadBinding.player("p"), () -> new Archive("p", 0));
        manager.markDirty("b", "q", DataThreadBinding.player("q"), () -> new Archive("q", 0));
        var first = manager.flushNow().toCompletableFuture();
        manager.markDirty("a", "p", DataThreadBinding.player("p"), () -> new Archive("p", 1));
        var next = manager.flushNow().toCompletableFuture();
        assertEquals(2, calls.size());
        calls.getFirst().future().complete(null);
        assertEquals(2, calls.size());
        assertFalse(next.isDone());
        calls.get(1).future().completeExceptionally(ZeroException.of(DataErrorCode.WRITE_FAILED, "test", null));
        assertEquals(1, first.join().successCount());
        assertEquals(1, first.join().failureCount());
        assertEquals(2, first.join().remainingDirtyCount());
        assertEquals(3, calls.size());
        assertEquals(new Archive("p", 1), calls.get(2).entity());
        calls.get(2).future().complete(null);
        assertEquals(1, next.join().successCount());
        assertEquals(1, next.join().remainingDirtyCount());
        assertEquals(1, manager.failures().size());
        assertEquals(3, manager.statistics().flushAttemptCount());
        assertEquals(2, manager.statistics().flushSuccessCount());
        assertEquals(1, manager.statistics().flushFailureCount());
    }

    /** 旧代际失败迟到时，不得污染已替换的新脏入口。 */
    @Test
    void lateFailureDoesNotPolluteNewGeneration() {
        List<SaveCall> calls = new ArrayList<>();
        var manager = manager();
        manager.registerTarget(new PersistenceTarget<>("a", repository("a", calls)));
        manager.markDirty("a", "p", DataThreadBinding.player("p"), () -> new Archive("p", 0));
        var first = manager.flushNow().toCompletableFuture();
        manager.markDirty("a", "p", DataThreadBinding.player("p"), () -> new Archive("p", 1));
        var second = manager.flushNow().toCompletableFuture();
        calls.getFirst().future().completeExceptionally(new IllegalStateException("old"));
        assertEquals(1, first.join().failureCount());
        assertEquals(2, calls.size());
        calls.get(1).future().complete(null);
        assertEquals(1, second.join().successCount());
        assertTrue(manager.failures().isEmpty());
    }

    /** 使用固定时钟令失败退避始终有效，确定性测试不依赖墙钟。 */
    private DefaultPersistenceManager manager() {
        return new DefaultPersistenceManager("test", new DirectPersistenceBindingExecutor(),
                Clock.fixed(java.time.Instant.EPOCH, java.time.ZoneOffset.UTC), Duration.ofSeconds(5),
                100, PersistenceRetryPolicy.defaults(), 2);
    }

    /** 只接受单项 save，批量部分成功不能成为清理脏表的依据。 */
    @SuppressWarnings("unchecked")
    private CrudRepository<String, Archive> repository(final String target, final List<SaveCall> calls) {
        return (CrudRepository<String, Archive>) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {CrudRepository.class}, (proxy, method, args) -> {
                    if (!method.getName().equals("save")) throw new AssertionError(method.getName());
                    var future = new CompletableFuture<Void>();
                    calls.add(new SaveCall(target, (Archive) args[0], future));
                    return future;
                });
    }

    /** 单次尚未完成的保存收据。 @author zn */
    private record SaveCall(String target, Archive entity, CompletableFuture<Void> future) { }

    /** 无可变字段的测试快照。 @author zn */
    private record Archive(String id, long version) implements VersionedEntity<String> {
        /** @return 带新版本的不可变快照。 */
        @Override public Archive withVersion(final long value) { return new Archive(id, value); }
    }
}
