package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 对报告中本地调度器并发不丢消息的结论做有限重复验证。 @author zn */
class ActorReportAuditTest {
    /** 每轮四个并发生产者投递同一 lane，所有完成信号和调用数必须对应。 */
    @Test void concurrentLocalDispatchCompletesEveryMessage() throws Exception {
        for (int round = 0; round < 30; round++) {
            LocalActorScheduler scheduler = new LocalActorScheduler();
            AtomicInteger calls = new AtomicInteger();
            scheduler.register(String.class, (context, message) -> {
                calls.incrementAndGet();
                return CompletableFuture.completedFuture(null);
            });
            List<Thread> threads = new ArrayList<>();
            List<CompletableFuture<Void>> done = new ArrayList<>();
            for (int producer = 0; producer < 4; producer++) {
                CompletableFuture<Void> result = new CompletableFuture<>();
                done.add(result);
                threads.add(Thread.ofVirtual().start(() -> {
                    try {
                        for (int i = 0; i < 8; i++) scheduler.dispatch(new ActorMessage(LaneKey.custom("lane"), "input"))
                                .toCompletableFuture().join();
                        result.complete(null);
                    } catch (RuntimeException failure) { result.completeExceptionally(failure); }
                }));
            }
            CompletableFuture.allOf(done.toArray(CompletableFuture[]::new)).get(5, java.util.concurrent.TimeUnit.SECONDS);
            for (Thread thread : threads) thread.join(1000);
            assertEquals(32, calls.get());
        }
    }
}
