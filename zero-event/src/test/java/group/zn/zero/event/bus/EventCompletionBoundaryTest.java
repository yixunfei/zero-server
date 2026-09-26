package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 事件续调异常和聚合异常所有权回归。 @author zn */
class EventCompletionBoundaryTest {
    /** 前一异步处理器完成后，后继注册失败也必须结束发布并继续派发。 */
    @Test void registrationFailureFinishesPublish() {
        var bus = new InMemoryEventBus(letter -> { });
        var gate = new CompletableFuture<Void>();
        var calls = new AtomicInteger();
        bus.register(EventType.INTERNAL, event -> gate);
        bus.register(EventType.INTERNAL, event -> new RegistrationFailureFuture());
        bus.register(EventType.INTERNAL, event -> { calls.incrementAndGet(); return CompletableFuture.completedFuture(null); });
        var result = bus.publish(event()).toCompletableFuture();
        gate.complete(null);
        assertTrue(result.isDone());
        assertThrows(CompletionException.class, result::join);
        assertEquals(1, calls.get());
    }

    /** 多次发布或死信失败不得修改调用者共享异常。 */
    @Test void failuresArePrivateToEachPublication() {
        var shared = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "shared", null);
        var bus = new InMemoryEventBus(letter -> { });
        bus.register(EventType.INTERNAL, event -> CompletableFuture.failedFuture(shared));
        bus.register(EventType.INTERNAL, event -> CompletableFuture.failedFuture(new IllegalStateException("individual")));
        Throwable first = assertThrows(CompletionException.class, () -> bus.publish(event()).toCompletableFuture().join()).getCause();
        Throwable second = assertThrows(CompletionException.class, () -> bus.publish(event()).toCompletableFuture().join()).getCause();
        assertNotSame(first, second);
        assertEquals(1, first.getSuppressed().length);
        assertEquals(1, second.getSuppressed().length);
        var failingSink = new InMemoryEventBus(letter -> { throw new IllegalStateException("sink"); });
        failingSink.register(EventType.INTERNAL, event -> CompletableFuture.failedFuture(shared));
        assertThrows(CompletionException.class, () -> failingSink.publish(event()).toCompletableFuture().join());
        assertEquals(0, shared.getSuppressed().length);
    }
    private static BasicZeroEvent event() { return new BasicZeroEvent("boundary", EventType.INTERNAL, "trace"); }
    /** 注册失败的外部阶段。 */
    private static final class RegistrationFailureFuture extends CompletableFuture<Void> {
        /** 注册回调失败。 */
        @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
            throw new IllegalStateException("registration failed");
        }
    }
}
