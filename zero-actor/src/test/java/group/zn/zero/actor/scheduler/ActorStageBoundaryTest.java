package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

/** 通用 CompletionStage 转换及回调注册边界回归。 @author zn */
class ActorStageBoundaryTest {
    /** 阶段不支持转换时，同 lane 仍须等待实际完成。 */
    @Test void unsupportedConversionKeepsLaneSuspended() {
        var scheduler = new LocalActorScheduler();
        var stage = new NoConversionFuture();
        var calls = new AtomicInteger();
        scheduler.register(String.class, (context, message) -> calls.getAndIncrement() == 0
                ? stage : CompletableFuture.completedFuture(null));
        var first = scheduler.dispatch(message("first")).toCompletableFuture();
        var second = scheduler.dispatch(message("second")).toCompletableFuture();
        assertEquals(1, calls.get());
        assertFalse(first.isDone());
        assertFalse(second.isDone());
        stage.complete(null);
        assertTrue(first.isDone());
        assertTrue(second.isDone());
        first.join();
        second.join();
        assertEquals(0, scheduler.statistics().pending());
        scheduler.close();
    }

    /** 注册失败不能遗留 active/pending；回调后再抛异常不能重复结算。 */
    @Test void registrationFailureAndCallbackThenThrowReleaseExactlyOnce() {
        for (boolean callbackFirst : new boolean[]{false, true}) {
            var work = new ArrayDeque<Runnable>();
            var scheduler = new ExecutorActorScheduler(work::add, new ActorSchedulerConfig(8, 8, 1));
            var calls = new AtomicInteger();
            scheduler.register(String.class, (context, message) -> calls.getAndIncrement() == 0
                    ? new RegistrationFailureFuture(callbackFirst) : CompletableFuture.completedFuture(null));
            var first = scheduler.dispatch(message("first")).toCompletableFuture();
            var second = scheduler.dispatch(message("second")).toCompletableFuture();
            assertDoesNotThrow(() -> { while (!work.isEmpty()) work.remove().run(); });
            assertTrue(first.isDone());
            assertTrue(second.isDone());
            if (callbackFirst) first.join();
            else assertThrows(CompletionException.class, first::join);
            second.join();
            assertEquals(2, calls.get());
            assertEquals(2, scheduler.statistics().completed());
            assertEquals(0, scheduler.statistics().pending());
            assertEquals(0, scheduler.statistics().active());
            scheduler.close();
        }
    }

    private static ActorMessage message(String value) { return new ActorMessage(LaneKey.player("p"), value); }
    /** 不支持 Future 转换的异步结果。 */
    private static final class NoConversionFuture extends CompletableFuture<Void> {
        /** @return 不返回；调用者只能使用 CompletionStage。 */
        @Override public CompletableFuture<Void> toCompletableFuture() { throw new UnsupportedOperationException(); }
    }
    /** 可选同步完成后抛错的阶段。 */
    private static final class RegistrationFailureFuture extends CompletableFuture<Void> {
        /** 是否先执行回调。 */
        private final boolean callbackFirst;
        private RegistrationFailureFuture(boolean callbackFirst) { this.callbackFirst = callbackFirst; }
        /** 注册回调；模拟外部阶段边界。 */
        @Override public CompletableFuture<Void> whenComplete(BiConsumer<? super Void, ? super Throwable> action) {
            if (callbackFirst) { complete(null); super.whenComplete(action); }
            throw new IllegalStateException("registration failed");
        }
    }
}
