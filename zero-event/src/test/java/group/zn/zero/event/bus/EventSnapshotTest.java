package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.handler.EventHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 注册版本、通用 stage 及完成交接竞态回归。 @author zn */
class EventSnapshotTest {
    /** 拦截器可重入修改注册，但本次 handler 必须来自发布开始的同一快照。 */
    @Test void reentrantRegistrationUsesOneSnapshotAndIdentityHandles() {
        var bus = new InMemoryEventBus(letter -> { });
        List<String> calls = new ArrayList<>();
        EventHandler duplicate = EventHandler.sync(event -> calls.add("old"));
        var first = bus.register(EventType.INTERNAL, duplicate);
        bus.register(EventType.INTERNAL, duplicate);
        var once = new AtomicInteger();
        bus.addInterceptor(event -> {
            if (once.getAndIncrement() == 0) {
                first.close();
                first.close();
                bus.register(EventType.INTERNAL, EventHandler.sync(ignored -> calls.add("new")));
            }
            return true;
        });
        bus.publish(event()).toCompletableFuture().join();
        assertEquals(List.of("old", "old"), calls);
        calls.clear();
        bus.publish(event()).toCompletableFuture().join();
        assertEquals(List.of("old", "new"), calls);
    }

    /** 完成恰好发生在回调注册内，连续阶段不得递归耗尽栈。 */
    @Test void inlineGeneralStagesAreIterativeWithoutFutureConversion() {
        var bus = new InMemoryEventBus(letter -> { });
        var count = new AtomicInteger();
        for (int i = 0; i < 3000; i++) {
            bus.register(EventType.INTERNAL, event -> { count.incrementAndGet(); return new InlineStage(); });
        }
        bus.publish(event()).toCompletableFuture().join();
        assertEquals(3000, count.get());
    }

    /** 异步挂起期间注销不会改写正在执行的快照，且取消输出不取消后续处理器。 */
    @Test void suspendedPublishKeepsHandlersAndCompletionIsPrivate() throws Exception {
        var bus = new InMemoryEventBus(letter -> { });
        var gate = new CompletableFuture<Void>();
        var count = new AtomicInteger();
        var blocking = bus.register(EventType.INTERNAL, event -> gate.minimalCompletionStage());
        var following = bus.register(EventType.INTERNAL, EventHandler.sync(event -> count.incrementAndGet()));
        var first = bus.publish(event()).toCompletableFuture();
        blocking.close();
        following.close();
        var second = bus.publish(event()).toCompletableFuture();
        assertTrue(second.isDone());
        assertNotSame(first, second);
        assertFalse(first.isDone());
        first.cancel(false);
        gate.complete(null);
        assertEquals(1, count.get());
        assertNull(second.get(2, TimeUnit.SECONDS));
    }

    /** 回调在写锁外执行，阻塞发布时另一线程仍能注册/注销。 */
    @Test void registrationDoesNotWaitForCallback() throws Exception {
        var bus = new InMemoryEventBus(letter -> { });
        var entered = new CompletableFuture<Void>();
        var release = new CompletableFuture<Void>();
        bus.addInterceptor(event -> { entered.complete(null); release.join(); return true; });
        Thread publisher = Thread.ofVirtual().start(() -> bus.publish(event()).toCompletableFuture().join());
        try {
            entered.get(2, TimeUnit.SECONDS);
            var written = new CompletableFuture<Void>();
            Thread writer = Thread.ofVirtual().start(() -> {
                bus.register(EventType.INTERNAL, EventHandler.sync(event -> { })).close();
                written.complete(null);
            });
            written.get(2, TimeUnit.SECONDS);
            writer.join();
        } finally { release.complete(null); publisher.join(); }
    }

    private static BasicZeroEvent event() { return new BasicZeroEvent("id", EventType.INTERNAL, "trace"); }

    /** 允许注册回调但不允许转换的通用 stage，完成注册的确定性竞态。 @author zn */
    private static final class InlineStage extends CompletableFuture<Void> {
        /** @param action 续调；注册期间完成。 @return 独立派生阶段。 */
        @Override public CompletableFuture<Void> whenComplete(final BiConsumer<? super Void, ? super Throwable> action) {
            complete(null);
            return super.whenComplete(action);
        }
        /** @return 永不返回；验证总线不依赖通用 stage 的转换支持。 */
        @Override public CompletableFuture<Void> toCompletableFuture() { throw new UnsupportedOperationException("no conversion"); }
    }
}
