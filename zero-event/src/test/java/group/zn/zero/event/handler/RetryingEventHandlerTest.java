package group.zn.zero.event.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 重试必须显式配置，成功停止、Error 不自动重放。 @author zn */
class RetryingEventHandlerTest {
    @Test void retriesOnlySelectedFailuresWithinBudget() {
        AtomicInteger calls = new AtomicInteger();
        var handler = new RetryingEventHandler(EventHandler.sync(event -> {
            if (calls.incrementAndGet() < 3) throw new IllegalStateException("retry");
        }), 3, failure -> failure instanceof IllegalStateException, Runnable::run);
        handler.handle(new BasicZeroEvent("id", EventType.INTERNAL, "trace"))
                .toCompletableFuture().join();
        assertEquals(3, calls.get());
        var fatal = new RetryingEventHandler(EventHandler.sync(event -> {
            calls.incrementAndGet();
            throw new AssertionError("fatal");
        }), 3, failure -> true, Runnable::run);
        assertThrows(CompletionException.class, () -> fatal.handle(
                new BasicZeroEvent("id", EventType.INTERNAL, "trace"))
                .toCompletableFuture().join());
        assertEquals(4, calls.get());
    }
}
