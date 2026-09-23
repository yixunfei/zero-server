package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.handler.EventHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 发布结果的调用方修改隔离、组合回调和嵌套派发契约。 @author zn */
class EventResultIsolationTest {
    /** 不可变测试事件。 */
    private static final BasicZeroEvent EVENT = new BasicZeroEvent("id", EventType.INTERNAL, "trace");

    @Test void conversionsCannotPoisonOtherPublicationsOrTheOriginalStage() {
        var bus = new InMemoryEventBus(letter -> { });
        bus.register(EventType.INTERNAL, EventHandler.sync(event -> { }));
        var result = bus.publish(EVENT);
        var modified = result.toCompletableFuture();
        modified.obtrudeException(new IllegalStateException("caller mutation"));
        assertThrows(CompletionException.class, modified::join);
        assertNull(result.toCompletableFuture().join());
        assertNull(bus.publish(EVENT).toCompletableFuture().join());
        var count = new AtomicInteger();
        result.thenRun(count::incrementAndGet).toCompletableFuture().join();
        result.whenComplete((value, failure) -> count.incrementAndGet()).toCompletableFuture().join();
        assertEquals(2, count.get());
        assertThrows(CompletionException.class, () -> result.thenRun(() -> {
            throw new IllegalStateException("dependent failed");
        }).toCompletableFuture().join());
        assertNull(result.toCompletableFuture().join());
    }

    @Test void nestedPublishAndAsyncHandoffPreserveOrderAndPrivateCompletion() {
        var nested = new InMemoryEventBus(letter -> { });
        var bus = new InMemoryEventBus(letter -> { });
        var count = new AtomicInteger();
        var gate = new CompletableFuture<Void>();
        nested.register(EventType.INTERNAL, EventHandler.sync(event -> count.incrementAndGet()));
        bus.register(EventType.INTERNAL, event -> nested.publish(event));
        bus.register(EventType.INTERNAL, event -> gate);
        bus.register(EventType.INTERNAL, EventHandler.sync(event -> count.incrementAndGet()));
        var result = bus.publish(EVENT).toCompletableFuture();
        assertEquals(1, count.get());
        gate.complete(null);
        result.join();
        assertEquals(2, count.get());
        result.obtrudeException(new IllegalStateException("private result mutation"));
        bus.publish(EVENT).toCompletableFuture().join();
        assertEquals(4, count.get());
    }
}
