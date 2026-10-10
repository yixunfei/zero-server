package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 活跃会话水位容量与排队关闭释放的边界验证。 @author zn */
class FrameLifecycleBoundaryTest {
    /** 所有缺帧策略都不能让消费完输入的 UID 绕过水位容量；leave 释放会话。 */
    @Test void consumedInputsKeepBoundedWatermarksUntilLeave() {
        for (MissingInputPolicy policy : MissingInputPolicy.values()) {
            try (var runtime = runtime(InputTimingPolicy.BUFFER, policy)) {
                runtime.submit(input("first", 2, 1)).toCompletableFuture().join();
                runtime.tick().toCompletableFuture().join();
                assertThrows(CompletionException.class,
                        () -> runtime.submit(input("second", 1, 2)).toCompletableFuture().join());
                runtime.leave("first").toCompletableFuture().join();
                runtime.submit(input("second", 1, 2)).toCompletableFuture().join();
                runtime.leave("second").toCompletableFuture().join();
                runtime.submit(input("first", 1, 2)).toCompletableFuture().join();
            }
        }
    }

    /** MARK 丢弃的迟到包也只能为有界数量的会话保存水位。 */
    @Test void markedLateInputsCannotGrowWatermarksWithoutLimit() {
        try (var runtime = runtime(InputTimingPolicy.MARK, MissingInputPolicy.EMPTY)) {
            runtime.tick().toCompletableFuture().join();
            runtime.submit(input("first", 1, 0)).toCompletableFuture().join();
            assertThrows(CompletionException.class,
                    () -> runtime.submit(input("second", 1, 0)).toCompletableFuture().join());
            runtime.leave("first").toCompletableFuture().join();
            runtime.submit(input("second", 1, 0)).toCompletableFuture().join();
        }
    }

    /** 关闭排队在同一 lane，清空已消费历史和未消费 payload，且不关闭共享调度器。 */
    @Test void queuedCloseReleasesAllRetainedState() throws Exception {
        var tasks = new ArrayDeque<Runnable>();
        try (var scheduler = new ExecutorActorScheduler(tasks::addLast)) {
            var runtime = new FrameMatchRuntime("release", FrameMatchConfig.defaults(),
                    (frame, batch) -> { }, event -> { }, event -> { }, scheduler);
            runtime.submit(input("first", 1, 1));
            drain(tasks);
            runtime.tick();
            drain(tasks);
            runtime.submit(input("first", 2, 3));
            drain(tasks);
            var close = runtime.closeAsync();
            runtime.closeAsync();
            drain(tasks);
            close.toCompletableFuture().join();
            for (String name : new String[]{"participants", "pending", "pendingSequences", "highestInputSequences", "lastInputs"}) {
                var field = FrameMatchRuntime.class.getDeclaredField(name);
                field.setAccessible(true);
                Object value = field.get(runtime);
                assertTrue(value instanceof Map<?, ?> map ? map.isEmpty() : ((Collection<?>) value).isEmpty(), name);
            }
            var buffered = FrameMatchRuntime.class.getDeclaredField("buffered");
            buffered.setAccessible(true);
            assertEquals(0, buffered.getInt(runtime));
            assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
        }
    }

    private static void drain(final ArrayDeque<Runnable> tasks) {
        while (!tasks.isEmpty()) tasks.removeFirst().run();
    }

    private static FrameMatchRuntime runtime(final InputTimingPolicy timing, final MissingInputPolicy missing) {
        return new FrameMatchRuntime("capacity", new FrameMatchConfig(timing, missing, 8, 2, 120, 1),
                (frame, batch) -> { }, event -> { }, event -> { });
    }

    private static FrameInput input(final String uid, final long sequence, final long frame) {
        return new FrameInput(uid, sequence, frame, 0, new byte[]{1}, "trace");
    }
}
