package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 输入拒绝与实际缓冲容量回归。 @author zn */
class FrameReportAuditTest {
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
    private FrameInput input(long seq, long frame) { return new FrameInput("u", seq, frame, 0, new byte[0], "t"); }
}
