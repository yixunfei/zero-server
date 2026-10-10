package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 输入拒绝与实际缓冲容量回归。 @author zn */
class FrameReportAuditTest {
    /** 模拟在副作用后失败不能重放同帧，也不能伪造已提交帧。 */
    @Test void partialSimulationFailureIsolatesMatch() {
        var effects = new java.util.concurrent.atomic.AtomicInteger();
        List<FrameCommitted> committed = new ArrayList<>();
        try (var runtime = new FrameMatchRuntime("failed", FrameMatchConfig.defaults(), (frame, batch) -> {
            effects.incrementAndGet();
            throw new IllegalStateException("partial simulation");
        }, committed::add, committed::add)) {
            runtime.submit(input(1, 1)).toCompletableFuture().join();
            assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
            assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
            assertThrows(CompletionException.class, () -> runtime.submit(input(2, 1)).toCompletableFuture().join());
            assertEquals(1, effects.get());
            assertEquals(0, runtime.frameNo());
            assertEquals(0, committed.size());
            assertEquals("partial simulation", runtime.simulationFailure().orElseThrow().getMessage());
            runtime.closeAsync().toCompletableFuture().join();
        }
    }
    /** 拒绝的输入重传仍然报告拒绝。 */
    @Test void rejectedInputIsNotMarkedAsAccepted() {
        try (FrameMatchRuntime runtime = runtime(new ArrayList<>())) {
            runtime.tick().toCompletableFuture().join();
            for (int i = 0; i < 2; i++) {
                assertThrows(CompletionException.class,
                        () -> runtime.submit(input(1, 0)).toCompletableFuture().join());
            }
        }
    }
    /** 覆盖同一席位不占用额外容量，提交当前已完成帧也必须拒绝。 */
    @Test void replacementUsesOneSlotAndCurrentFrameIsLate() {
        List<FrameCommitted> events = new ArrayList<>();
        try (FrameMatchRuntime runtime = runtime(events)) {
            for (int i = 1; i <= 10; i++) runtime.submit(input(i, 1)).toCompletableFuture().join();
            runtime.tick().toCompletableFuture().join();
            assertEquals(10, events.getFirst().batch().inputs().getFirst().inputSeq());
            assertThrows(CompletionException.class, () -> runtime.submit(input(11, 1)).toCompletableFuture().join());
            runtime.submit(input(12, 2)).toCompletableFuture().join();
        }
    }
    /** 一个框架调度器可同时承载多个独立对局。 */
    @Test void matchesCanShareSchedulerWithoutHandlerCollision() {
        var scheduler = new group.zn.zero.actor.scheduler.LocalActorScheduler();
        try (FrameMatchRuntime first = new FrameMatchRuntime("a", FrameMatchConfig.defaults(), (f, b) -> { }, e -> { }, e -> { }, scheduler);
             FrameMatchRuntime second = new FrameMatchRuntime("b", FrameMatchConfig.defaults(), (f, b) -> { }, e -> { }, e -> { }, scheduler)) {
            first.tick().toCompletableFuture().join();
            assertEquals(1, first.frameNo());
            assertEquals(0, second.frameNo());
            first.close();
            second.tick().toCompletableFuture().join();
            assertEquals(1, second.frameNo());
        }
    }
    /** BUFFER 策略将迟到输入放入下一帧，避免永远占据旧帧槽。 */
    @Test void bufferPolicyConsumesLateInputInNextFrame() {
        List<FrameCommitted> events = new ArrayList<>();
        try (FrameMatchRuntime runtime = new FrameMatchRuntime("buffer", new FrameMatchConfig(InputTimingPolicy.BUFFER,
                MissingInputPolicy.EMPTY, 8, 1), (f, b) -> { }, events::add, e -> { })) {
            runtime.tick().toCompletableFuture().join();
            runtime.submit(input(1, 0)).toCompletableFuture().join();
            runtime.tick().toCompletableFuture().join();
            assertEquals(1, events.getLast().batch().inputs().size());
            runtime.submit(input(2, 3)).toCompletableFuture().join();
        }
    }
    private FrameMatchRuntime runtime(List<FrameCommitted> events) {
        return new FrameMatchRuntime("m", new FrameMatchConfig(InputTimingPolicy.REJECT,
                MissingInputPolicy.EMPTY, 8, 1), (frame, batch) -> { }, events::add, event -> { });
    }
    /** 关闭前已入队但尚未执行的输入和 tick 均失败，其他对局仍能推进。 */
    @Test void queuedCommandsRejectAfterCloseWithoutStoppingSharedScheduler() {
        var tasks = new java.util.ArrayDeque<Runnable>();
        var advances = new java.util.concurrent.atomic.AtomicInteger();
        var notifications = new ArrayList<FrameCommitted>();
        try (var scheduler = new group.zn.zero.actor.scheduler.ExecutorActorScheduler(tasks::addLast);
             var first = new FrameMatchRuntime("closing", FrameMatchConfig.defaults(),
                     (frame, batch) -> advances.incrementAndGet(), notifications::add, notifications::add, scheduler);
             var second = new FrameMatchRuntime("continuing", FrameMatchConfig.defaults(),
                     (frame, batch) -> { }, event -> { }, event -> { }, scheduler)) {
            var submit = first.submit(input(1, 1)).toCompletableFuture();
            var tick = first.tick().toCompletableFuture();
            var leave = first.leave("u").toCompletableFuture();
            var closing = first.closeAsync();
            var other = second.tick().toCompletableFuture();
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            assertThrows(CompletionException.class, submit::join);
            assertThrows(CompletionException.class, tick::join);
            assertThrows(CompletionException.class, leave::join);
            other.join();
            assertEquals(0, advances.get());
            assertEquals(0, notifications.size());
            assertEquals(0, first.frameNo());
            assertEquals(1, second.frameNo());
            closing.toCompletableFuture().join();
            var secondClose = second.closeAsync();
            while (!tasks.isEmpty()) tasks.removeFirst().run();
            secondClose.toCompletableFuture().join();
        }
    }
    private FrameInput input(long seq, long frame) { return new FrameInput("u", seq, frame, 0, new byte[0], "t"); }
}
