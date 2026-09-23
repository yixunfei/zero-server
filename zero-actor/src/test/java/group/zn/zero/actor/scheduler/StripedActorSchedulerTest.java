package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.handler.ActorHandler;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicIntegerArray;
import org.junit.jupiter.api.Test;

/** 分段调度的并发、回收及注册契约回归。 @author zn */
class StripedActorSchedulerTest {
    /** 多生产者同 lane 不重叠，每生产者 FIFO，包含大量空闲回收与重新投递。 */
    @Test
    void concurrentProducersPreserveOrderAndCompleteEveryMessage() throws Exception {
        exerciseConcurrentLanes(1);
        exerciseConcurrentLanes(257);
    }

    private void exerciseConcurrentLanes(final int laneCount) throws Exception {
        int producers = 8;
        int messages = 2048;
        var workers = Executors.newFixedThreadPool(4);
        var callers = Executors.newFixedThreadPool(producers);
        try {
            var scheduler = new ExecutorActorScheduler(workers);
            int[][] sequences = new int[laneCount][producers];
            var active = new AtomicIntegerArray(laneCount);
            scheduler.register(Work.class, ActorHandler.sync((context, message) -> {
                Work work = (Work) message.payload();
                assertEquals(1, active.incrementAndGet(work.lane()));
                try {
                    assertEquals(sequences[work.lane()][work.producer()]++, work.sequence());
                } finally {
                    active.decrementAndGet(work.lane());
                }
            }));
            var start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> submitted = new ArrayList<>();
            for (int producer = 0; producer < producers; producer++) {
                final int id = producer;
                submitted.add(callers.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    List<CompletableFuture<Void>> pending = new ArrayList<>();
                    for (int sequence = 0; sequence < messages; sequence++) {
                        int lane = sequence % laneCount;
                        pending.add(scheduler.dispatch(new ActorMessage(LaneKey.player(Integer.toString(lane)),
                                new Work(id, sequence / laneCount, lane))).toCompletableFuture());
                        // 周期性等待完成，覆盖 drain 空队列回收与新投递交错。
                        if ((sequence & 127) == 127) {
                            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                            pending.clear();
                        }
                    }
                    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(10, TimeUnit.SECONDS);
                    return null;
                }));
            }
            start.countDown();
            for (var future : submitted) future.get(20, TimeUnit.SECONDS);
            int total = 0;
            for (int[] lane : sequences) for (int sequence : lane) total += sequence;
            assertEquals(producers * messages, total);
        } finally {
            callers.shutdownNow();
            workers.shutdownNow();
            assertTrue(callers.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 精确类型优先，父类型匹配按注册顺序，旧注销句柄不会删除同对象的新注册。 */
    @Test
    void orderedSnapshotsPreserveSelectionAndRegistrationIdentity() {
        var scheduler = new ExecutorActorScheduler(Runnable::run);
        List<String> calls = new ArrayList<>();
        ActorHandler exact = ActorHandler.sync((context, message) -> calls.add("exact"));
        scheduler.register(CharSequence.class, ActorHandler.sync((context, message) -> calls.add("first")));
        scheduler.register(Object.class, ActorHandler.sync((context, message) -> calls.add("second")));
        ActorSubscription old = scheduler.register(String.class, exact);
        assertThrows(ZeroException.class, () -> scheduler.register(String.class, exact));
        send(scheduler, "one");
        old.close();
        send(scheduler, "two");
        ActorSubscription current = scheduler.register(String.class, exact);
        old.close();
        send(scheduler, "three");
        current.close();
        send(scheduler, "four");
        assertEquals(List.of("exact", "first", "exact", "first"), calls);
    }

    /** 异步等待期间注册变化只影响之后提交的消息，completion 可以重入投递。 */
    @Test
    void queuedMessagesKeepTheirHandlerAndCompletionMayDispatchAgain() {
        var scheduler = new ExecutorActorScheduler(Runnable::run);
        var gate = new CompletableFuture<Void>();
        List<String> calls = new ArrayList<>();
        ActorSubscription initial = scheduler.register(String.class, (context, message) -> {
            calls.add("old:" + message.payload());
            return "first".equals(message.payload()) ? gate : CompletableFuture.completedFuture(null);
        });
        var first = scheduler.dispatch(new ActorMessage(LaneKey.player("same"), "first"));
        var queued = scheduler.dispatch(new ActorMessage(LaneKey.player("same"), "queued"));
        initial.close();
        scheduler.register(String.class, ActorHandler.sync((context, message) -> calls.add("new:" + message.payload())));
        var reentrant = first.thenCompose(ignored -> scheduler.dispatch(new ActorMessage(LaneKey.player("same"), "again")));
        gate.complete(null);
        CompletableFuture.allOf(queued.toCompletableFuture(), reentrant.toCompletableFuture()).join();
        assertEquals(List.of("old:first", "old:queued", "new:again"), calls);
    }

    private void send(final ActorScheduler scheduler, final String message) {
        scheduler.dispatch(new ActorMessage(LaneKey.player("selection"), message)).toCompletableFuture().join();
    }

    /** future 在完成检查与回调注册之间完成时，direct executor 不得递归至栈溢出。 */
    @Test
    void synchronousCompletionDuringCallbackRegistrationDoesNotRecurse() throws Exception {
        var scheduler = new ExecutorActorScheduler(Runnable::run, new ActorSchedulerConfig(100_000, 100_000, 64));
        var gate = new CompletableFuture<Void>();
        scheduler.register(Integer.class, (context, message) -> (Integer) message.payload() == -1
                ? gate : new CompletesOnRegistration());
        var first = scheduler.dispatch(new ActorMessage(LaneKey.player("race"), -1));
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        pending.add(first.toCompletableFuture());
        for (int i = 0; i < 20_000; i++) {
            pending.add(scheduler.dispatch(new ActorMessage(LaneKey.player("race"), i)).toCompletableFuture());
        }
        gate.complete(null);
        CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
    }

    /** 哈希碰撞不合并 lane 的执行权，等待异步结果的 lane 不阻塞同段其他 lane。 */
    @Test
    void collidingLaneKeysRemainIndependent() {
        LaneKey firstKey = LaneKey.player("Aa");
        LaneKey secondKey = LaneKey.player("BB");
        assertEquals(firstKey.hashCode(), secondKey.hashCode());
        var scheduler = new ExecutorActorScheduler(Runnable::run);
        var pending = new CompletableFuture<Void>();
        scheduler.register(String.class, (context, message) -> message.laneKey().equals(firstKey)
                ? pending : CompletableFuture.completedFuture(null));
        var first = scheduler.dispatch(new ActorMessage(firstKey, "first")).toCompletableFuture();
        var second = scheduler.dispatch(new ActorMessage(secondKey, "second")).toCompletableFuture();
        assertTrue(second.isDone());
        assertTrue(!first.isDone());
        pending.complete(null);
        first.join();
    }

    /** 注册/注销并发发布快照时始终可读到精确处理器或父类型回退，不能出现残缺表。 */
    @Test
    void registrationsCanChangeWhileOtherThreadsDispatch() throws Exception {
        var scheduler = new ExecutorActorScheduler(Runnable::run);
        var count = new java.util.concurrent.atomic.AtomicInteger();
        ActorHandler handler = ActorHandler.sync((context, message) -> count.incrementAndGet());
        scheduler.register(Object.class, handler);
        var executor = Executors.newFixedThreadPool(2);
        try {
            var start = new CountDownLatch(1);
            var registration = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 2000; i++) scheduler.register(String.class, handler).close();
                return null;
            });
            var dispatch = executor.submit(() -> {
                assertTrue(start.await(5, TimeUnit.SECONDS));
                for (int i = 0; i < 10_000; i++) send(scheduler, "message");
                return null;
            });
            start.countDown();
            registration.get(10, TimeUnit.SECONDS);
            dispatch.get(10, TimeUnit.SECONDS);
            assertEquals(10_000, count.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    /** 确定性构造完成检查与回调注册的竞态。 @author zn */
    private static final class CompletesOnRegistration extends CompletableFuture<Void> {
        /** 完成后同步执行注册回调，模拟任意外部线程已恰好完成 future 的情况。 */
        @Override public CompletableFuture<Void> whenComplete(
                final java.util.function.BiConsumer<? super Void, ? super Throwable> action) {
            complete(null);
            return super.whenComplete(action);
        }
    }

    /** 测试消息，序号在每个生产者和 lane 内独立递增。 @author zn */
    private record Work(int producer, int sequence, int lane) { }
}
