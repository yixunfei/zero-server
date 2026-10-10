package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.event.interceptor.EventInterceptor;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 完成钩子针对本次进入集合逆序配对，不因失败漏清理。 @author zn */
class EventInterceptorCompletionTest {
    /** 首次派发拒绝及死信接收器失败仍须逆序清理所有已进入拦截器。 */
    @Test void dispatchRejectionAndBrokenSinkStillUnwindHooks() {
        var calls = new ArrayList<String>();
        var shared = new IllegalStateException("sink");
        var bus = new InMemoryEventBus(letter -> { throw shared; }, java.time.Clock.systemUTC(),
                command -> { throw new java.util.concurrent.RejectedExecutionException("dispatch"); });
        bus.addInterceptor(hook("a", true, calls, true));
        bus.addInterceptor(hook("b", true, calls, false));
        bus.register(EventType.INTERNAL, event -> CompletableFuture.completedFuture(null));
        var failure = assertThrows(CompletionException.class, () -> bus.publish(event()).toCompletableFuture().join());
        assertEquals(List.of("before:a", "before:b", "after:b", "after:a"), calls);
        assertEquals(2, failure.getCause().getSuppressed().length);
        assertEquals(0, shared.getSuppressed().length);
    }
    /** 拒绝方也清理，未进入的拦截器不清理；清理失败不覆盖发布失败。 */
    @Test void rejectionUnwindsOnlyEnteredHooksAndAggregatesFailures() {
        var calls = new ArrayList<String>();
        var sink = new InMemoryDeadLetterSink();
        var bus = new InMemoryEventBus(sink);
        bus.addInterceptor(hook("a", true, calls, true));
        bus.addInterceptor(hook("b", false, calls, true));
        bus.addInterceptor(hook("c", true, calls, false));
        var failure = assertThrows(CompletionException.class, () -> bus.publish(event()).toCompletableFuture().join());
        assertEquals(List.of("before:a", "before:b", "after:b", "after:a"), calls);
        assertEquals(2, failure.getCause().getSuppressed().length);
        assertEquals(3, sink.deadLetters().size());
    }

    /** 异步业务真正完成后才通知；完成后修改注册不影响当前配对集合。 */
    @Test void asynchronousCompletionUsesPublicationSnapshot() {
        var calls = new ArrayList<String>();
        var bus = new InMemoryEventBus(new InMemoryDeadLetterSink());
        var subscription = bus.addInterceptor(hook("a", true, calls, false));
        var work = new CompletableFuture<Void>();
        bus.register(EventType.INTERNAL, event -> work);
        var result = bus.publish(event()).toCompletableFuture();
        subscription.close();
        assertFalse(result.isDone());
        assertEquals(List.of("before:a"), calls);
        work.complete(null);
        result.join();
        assertEquals(List.of("before:a", "after:a"), calls);
    }

    /** 空处理器链也执行完成钩子；钩子异常不污染下一次发布的外部异常。 */
    @Test void emptyDispatchAndThrowingBeforeStillUnwind() {
        var failure = new IllegalStateException("shared");
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var bus = new InMemoryEventBus(new InMemoryDeadLetterSink());
        bus.addInterceptor(new EventInterceptor() {
            @Override public boolean beforePublish(final ZeroEvent event) { throw failure; }
            @Override public void afterPublish(final ZeroEvent event, final Throwable cause) {
                count.incrementAndGet(); throw failure;
            }
        });
        for (int i = 0; i < 2; i++) assertThrows(CompletionException.class, () -> bus.publish(event()).toCompletableFuture().join());
        assertEquals(2, count.get());
        assertEquals(0, failure.getSuppressed().length);
        var successful = new InMemoryEventBus(new InMemoryDeadLetterSink());
        var calls = new ArrayList<String>();
        successful.addInterceptor(hook("empty", true, calls, false));
        successful.publish(event()).toCompletableFuture().join();
        assertEquals(List.of("before:empty", "after:empty"), calls);
    }

    private EventInterceptor hook(final String name, final boolean accept, final List<String> calls, final boolean fail) {
        return new EventInterceptor() {
            @Override public boolean beforePublish(final ZeroEvent event) { calls.add("before:" + name); return accept; }
            @Override public void afterPublish(final ZeroEvent event, final Throwable failure) {
                calls.add("after:" + name);
                if (fail) throw new IllegalStateException("cleanup");
            }
        };
    }

    private ZeroEvent event() { return new BasicZeroEvent("completion", EventType.INTERNAL, "trace"); }
}
