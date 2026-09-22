package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.event.handler.EventHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 失败隔离、聚合与死信容量回归。 @author zn */
class EventReportAuditTest {
    /** 异步失败跨线程串联，后一个 handler 不得在前一个完成前进入。 */
    @Test void asynchronousFailuresAreOrderedAndAllRetained() throws Exception {
        var sink = new InMemoryDeadLetterSink(128);
        var bus = new InMemoryEventBus(sink);
        List<CompletableFuture<Void>> stages = new ArrayList<>();
        var entered = new java.util.concurrent.atomic.AtomicInteger();
        for (int i = 0; i < 64; i++) {
            CompletableFuture<Void> stage = new CompletableFuture<>();
            stages.add(stage);
            bus.register(EventType.INTERNAL, event -> { entered.incrementAndGet(); return stage; });
        }
        var result = bus.publish(new BasicZeroEvent("async", EventType.INTERNAL, "trace")).toCompletableFuture();
        for (int i = 0; i < stages.size(); i++) {
            assertEquals(i + 1, entered.get());
            CompletableFuture<Void> stage = stages.get(i);
            Thread thread = Thread.ofVirtual().start(() -> stage.completeExceptionally(new IllegalStateException("failed")));
            thread.join(2000);
        }
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        assertEquals(63, failure.getCause().getSuppressed().length);
        assertEquals(64, sink.deadLetters().size());
    }
    /** 同步及异步失败均不得阻断后续处理器，最后汇总失败。 */
    @Test void failuresAreAggregatedAfterAllListenersRun() {
        InMemoryDeadLetterSink sink = new InMemoryDeadLetterSink(2);
        InMemoryEventBus bus = new InMemoryEventBus(sink);
        List<String> calls = new ArrayList<>();
        bus.register(EventType.INTERNAL, EventHandler.sync(event -> { calls.add("a"); throw new IllegalStateException("first"); }));
        bus.register(EventType.INTERNAL, event -> { calls.add("b"); return CompletableFuture.failedFuture(new IllegalArgumentException("second")); });
        bus.register(EventType.INTERNAL, EventHandler.sync(event -> calls.add("c")));
        var event = new BasicZeroEvent("e", EventType.INTERNAL, "t");
        CompletionException failure = assertThrows(CompletionException.class, () -> bus.publish(event).toCompletableFuture().join());
        assertEquals(List.of("a", "b", "c"), calls);
        assertEquals(1, failure.getCause().getSuppressed().length);
        assertEquals(2, sink.deadLetters().size());
        assertThrows(CompletionException.class, () -> bus.publish(event).toCompletableFuture().join());
        assertEquals(2, sink.deadLetters().size());
        assertEquals(2, sink.droppedCount());
    }
}
