package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 异步续调被拒绝仍保留处理器失败与死信。 @author zn */
class EventResumeRejectionTest {
    /** 首次执行成功，异步失败后拒绝续调；两个失败都必须可见。 */
    @Test void rejectionRetainsHandlerFailureAndRecordsBothDeadLetters() {
        var calls = new AtomicInteger();
        var later = new AtomicInteger();
        var sink = new InMemoryDeadLetterSink();
        var bus = new InMemoryEventBus(sink, Clock.systemUTC(), task -> {
            if (calls.incrementAndGet() > 1) throw new RejectedExecutionException("closed");
            task.run();
        });
        var pending = new CompletableFuture<Void>();
        bus.register(EventType.INTERNAL, event -> pending);
        bus.register(EventType.INTERNAL, event -> { later.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        var result = bus.publish(new BasicZeroEvent("resume", EventType.INTERNAL, "trace")).toCompletableFuture();
        pending.completeExceptionally(new IllegalStateException("business failed"));
        var failure = assertThrows(CompletionException.class, result::join);
        assertEquals(0, later.get());
        assertEquals(2, sink.deadLetters().size());
        assertEquals(1, failure.getCause().getSuppressed().length);
    }
}
