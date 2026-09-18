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
