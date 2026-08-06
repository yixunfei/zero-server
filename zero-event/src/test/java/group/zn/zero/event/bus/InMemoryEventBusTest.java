package group.zn.zero.event.bus;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.event.BasicZeroEvent;
import group.zn.zero.event.EventType;
import group.zn.zero.event.ZeroEvent;
import group.zn.zero.event.deadletter.DeadLetter;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.event.handler.EventHandler;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/**
 * 内存事件总线测试。
 *
 * @author zn
 */
class InMemoryEventBusTest {

    /**
     * 验证处理器按优先级和注册顺序执行。
     */
    @Test
    void publishShouldInvokeHandlersByPriority() {
        InMemoryDeadLetterSink deadLetterSink = new InMemoryDeadLetterSink();
        EventBus eventBus = new InMemoryEventBus(deadLetterSink);
        List<String> calls = new ArrayList<>();

        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> calls.add("low")), 10);
        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> calls.add("high")), -1);
        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> calls.add("middle")), 0);

        eventBus.publish(event()).toCompletableFuture().join();

        assertEquals(List.of("high", "middle", "low"), calls);
        assertTrue(deadLetterSink.deadLetters().isEmpty());
    }

    /**
     * 验证拦截器可以短路发布。
     */
    @Test
    void interceptorShouldStopPublishWhenReturnFalse() {
        InMemoryDeadLetterSink deadLetterSink = new InMemoryDeadLetterSink();
        EventBus eventBus = new InMemoryEventBus(deadLetterSink);
        List<String> calls = new ArrayList<>();

        eventBus.addInterceptor(event -> false);
        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> calls.add(event.eventId())));

        eventBus.publish(event()).toCompletableFuture().join();

        assertTrue(calls.isEmpty());
        assertTrue(deadLetterSink.deadLetters().isEmpty());
    }

    /**
     * 验证处理器异常进入死信并绑定错误码。
     */
    @Test
    void failedHandlerShouldRecordDeadLetter() {
        InMemoryDeadLetterSink deadLetterSink = new InMemoryDeadLetterSink();
        EventBus eventBus = new InMemoryEventBus(deadLetterSink);
        ZeroException failure = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "boom", null);

        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> {
            throw failure;
        }));

        try {
            eventBus.publish(event()).toCompletableFuture().join();
        } catch (CompletionException ex) {
            assertEquals(failure, ex.getCause());
        }

        assertEquals(1, deadLetterSink.deadLetters().size());
        assertEquals(SystemErrorCode.SYSTEM_ERROR, deadLetterSink.deadLetters().get(0).errorCode());
        assertEquals("trace-1", deadLetterSink.deadLetters().get(0).event().traceId());
    }

    /**
     * 验证死信接收器失败时保留原始异常。
     */
    @Test
    void deadLetterSinkFailureShouldSuppressOnOriginalFailure() {
        RuntimeException sinkFailure = new RuntimeException("sink failed");
        DeadLetterSink deadLetterSink = new FailingDeadLetterSink(sinkFailure);
        EventBus eventBus = new InMemoryEventBus(deadLetterSink);
        ZeroException failure = ZeroException.of(SystemErrorCode.SYSTEM_ERROR, "boom", null);

        eventBus.register(EventType.INTERNAL, EventHandler.sync(event -> {
            throw failure;
        }));

        try {
            eventBus.publish(event()).toCompletableFuture().join();
        } catch (CompletionException ex) {
            assertEquals(failure, ex.getCause());
            assertEquals(sinkFailure, ex.getCause().getSuppressed()[0]);
        }
    }

    private ZeroEvent event() {
        return new BasicZeroEvent("event-1", EventType.INTERNAL, "trace-1");
    }

    /**
     * 固定失败的死信接收器。
     */
    private static final class FailingDeadLetterSink implements DeadLetterSink {

        /**
         * 固定失败异常。
         */
        private final RuntimeException failure;

        /**
         * 创建固定失败的死信接收器。
         *
         * @param failure 固定失败异常；不可为空。
         */
        private FailingDeadLetterSink(final RuntimeException failure) {
            this.failure = failure;
        }

        /**
         * 记录死信事件。
         *
         * @param deadLetter 死信记录；不可为空。
         * @throws RuntimeException 固定抛出失败异常。
         */
        @Override
        public void record(final DeadLetter deadLetter) {
            throw failure;
        }
    }
}
