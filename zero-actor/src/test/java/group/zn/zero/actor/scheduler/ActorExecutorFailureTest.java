package group.zn.zero.actor.scheduler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.LaneKey;
import group.zn.zero.actor.error.ActorErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 执行器创建线程等严重失败不能泄漏 Lane 或准入许可。 @author zn */
class ActorExecutorFailureTest {
    /** 首次提交失败要结束原消息，后续相同 Lane 可以重新准入。 */
    @Test void initialExecutorErrorReleasesLaneAndCapacity() {
        var failure = new AssertionError("executor unavailable");
        var submissions = new AtomicInteger();
        try (var scheduler = new ExecutorActorScheduler(command -> {
            if (submissions.getAndIncrement() == 0) throw failure;
            command.run();
        }, new ActorSchedulerConfig(1, 1, 1))) {
            scheduler.register(String.class, (context, message) -> CompletableFuture.completedFuture(null));
            assertRejected(scheduler.dispatch(message("first")).toCompletableFuture(), failure);
            scheduler.dispatch(message("second")).toCompletableFuture().join();
            assertEquals(0, scheduler.statistics().pending());
            assertEquals(0, scheduler.statistics().active());
            assertEquals(1, scheduler.statistics().rejected());
            assertEquals(1, scheduler.statistics().completed());
        }
    }

    /** 异步完成后续调失败也必须通知已排队消息，不能吞入 whenComplete 的返回值。 */
    @Test void asynchronousResubmissionErrorCompletesQueuedMessages() {
        var failure = new AssertionError("resubmission unavailable");
        var submissions = new AtomicInteger();
        var release = new CompletableFuture<Void>();
        try (var scheduler = new ExecutorActorScheduler(command -> {
            if (submissions.getAndIncrement() == 1) throw failure;
            command.run();
        }, new ActorSchedulerConfig(2, 2, 1))) {
            scheduler.register(String.class, (context, message) -> "first".equals(message.payload())
                    ? release : CompletableFuture.completedFuture(null));
            var first = scheduler.dispatch(message("first")).toCompletableFuture();
            var second = scheduler.dispatch(message("second")).toCompletableFuture();
            release.complete(null);
            first.join();
            assertRejected(second, failure);
            scheduler.dispatch(message("third")).toCompletableFuture().join();
            assertEquals(0, scheduler.statistics().pending());
            assertEquals(0, scheduler.statistics().active());
            assertEquals(1, scheduler.statistics().rejected());
            assertEquals(2, scheduler.statistics().completed());
        }
    }

    private static ActorMessage message(final String payload) {
        return new ActorMessage(LaneKey.player("executor-failure"), payload);
    }

    private static void assertRejected(final CompletableFuture<Void> result, final Error cause) {
        // 先断言已完成，避免回归版本把 join 永久挂起。
        assertTrue(result.isDone());
        CompletionException failure = assertThrows(CompletionException.class, result::join);
        ZeroException rejected = (ZeroException) failure.getCause();
        assertEquals(ActorErrorCode.EXECUTOR_REJECTED, rejected.errorCode());
        assertSame(cause, rejected.getCause());
    }
}
