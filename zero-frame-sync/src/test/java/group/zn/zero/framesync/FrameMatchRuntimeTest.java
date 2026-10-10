package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class FrameMatchRuntimeTest {
    /** 通知失败不能再次执行已经成功的模拟。 */
    @Test void notificationFailureDoesNotReplayCommittedSimulation() {
        for (boolean failEvent : new boolean[]{true, false}) {
            List<Long> simulated = new ArrayList<>();
            AtomicBoolean fail = new AtomicBoolean(true);
            FrameMatchRuntime runtime = new FrameMatchRuntime("notify", FrameMatchConfig.defaults(),
                    (frame, batch) -> simulated.add(frame),
                    event -> { if (failEvent && fail.getAndSet(false)) throw new IllegalStateException("event"); },
                    event -> { if (!failEvent && fail.getAndSet(false)) throw new IllegalStateException("broadcast"); });
            assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
            assertEquals(1, runtime.frameNo());
            runtime.tick().toCompletableFuture().join();
            assertEquals(List.of(1L, 2L), simulated);
        }
    }

    /** 有限历史淘汰不能驱逐仍未消费的输入身份。 */
    @Test void pendingSequenceSurvivesHistoryEviction() {
        List<Long> simulated = new ArrayList<>();
        FrameMatchRuntime runtime = new FrameMatchRuntime("dedupe",
                new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 8, 2),
                (frame, batch) -> batch.inputs().forEach(input -> simulated.add(input.inputSeq())), event -> { }, event -> { });
        for (long[] pair : new long[][]{{1, 1}, {2, 2}, {3, 2}, {1, 2}}) {
            runtime.submit(new FrameInput("u", pair[0], pair[1], 0, new byte[]{1}, "t")).toCompletableFuture().join();
        }
        runtime.tick().toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(List.of(1L, 3L), simulated);
    }
    /** 参与者和输入容量分别计数，离开释放历史，不向后续帧重放离开者输入。 */
    @Test void leaveReleasesRepeatHistoryWithoutLosingRemainingPlayers() {
        List<FrameCommitted> frames = new ArrayList<>();
        var runtime = new FrameMatchRuntime("leave",
                new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.REPEAT_LAST, 8, 1, 120, 2),
                (frame, batch) -> { }, frames::add, event -> { });
        runtime.submit(new FrameInput("a", 1, 1, 0, new byte[]{1}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        runtime.submit(new FrameInput("b", 1, 2, 0, new byte[]{2}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(List.of("a", "b"), frames.getLast().batch().inputs().stream().map(FrameInput::uid).toList());
        runtime.leave("a").toCompletableFuture().join();
        runtime.submit(new FrameInput("c", 1, 3, 0, new byte[]{3}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(List.of("b", "c"), frames.getLast().batch().inputs().stream().map(FrameInput::uid).toList());
    }

    @Test void frameClockAndOrderingAreAuthoritative() {
        List<FrameCommitted> committed = new ArrayList<>();
        FrameMatchRuntime runtime = new FrameMatchRuntime("m", FrameMatchConfig.defaults(), (f, b) -> {}, committed::add, committed::add);
        runtime.submit(new FrameInput("b", 1, 1, 0, new byte[] {1}, "t")).toCompletableFuture().join();
        runtime.submit(new FrameInput("a", 1, 1, 0, new byte[] {2}, "t")).toCompletableFuture().join();
        runtime.submit(new FrameInput("a", 1, 1, 1, new byte[] {2}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(1, runtime.frameNo());
        assertEquals(List.of("a", "b"), committed.getFirst().batch().inputs().stream().map(FrameInput::uid).toList());
    }

    @Test void latePolicyAndCapacityAreExplicit() {
        FrameMatchConfig config = new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 1, 1);
        FrameMatchRuntime runtime = new FrameMatchRuntime("m", config, (f, b) -> {}, e -> {}, e -> {});
        runtime.tick().toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> runtime.submit(new FrameInput("u", 1, 0, 1, new byte[] {1}, "t")).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> runtime.submit(new FrameInput("u", 2, 2, 0, new byte[] {1, 2}, "t")).toCompletableFuture().join());
    }

    /** 远期窗口边界可配置；被拒绝的输入不得占用参与者或输入容量。 */
    @Test void futureWindowRejectsOnlyBeyondConfiguredBoundary() {
        FrameMatchConfig config = new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 32, 1, 2);
        FrameMatchRuntime runtime = new FrameMatchRuntime("future", config, (f, b) -> { }, event -> { }, event -> { });
        runtime.submit(new FrameInput("u", 1, 2, 0, new byte[]{1}, "t")).toCompletableFuture().join();
        assertThrows(CompletionException.class,
                () -> runtime.submit(new FrameInput("v", 1, 3, 0, new byte[]{1}, "t")).toCompletableFuture().join());
        assertThrows(CompletionException.class,
                () -> runtime.submit(new FrameInput("w", 1, Long.MAX_VALUE, 0, new byte[]{1}, "t"))
                        .toCompletableFuture().join());
        runtime.tick().toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(2, runtime.frameNo());
        runtime.submit(new FrameInput("v", 1, 4, 0, new byte[]{1}, "t")).toCompletableFuture().join();
    }

    /** tick 推进后，远期窗口随当前帧移动。 */
    @Test void futureWindowMovesWithFrameClock() {
        FrameMatchConfig config = new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 32, 2, 1);
        FrameMatchRuntime runtime = new FrameMatchRuntime("moving-future", config, (f, b) -> { }, event -> { }, event -> { });
        assertThrows(CompletionException.class,
                () -> runtime.submit(new FrameInput("u", 1, 2, 0, new byte[]{1}, "t")).toCompletableFuture().join());
        runtime.tick().toCompletableFuture().join();
        runtime.submit(new FrameInput("u", 1, 2, 0, new byte[]{1}, "t")).toCompletableFuture().join();
    }

    /** MARK 策略消费后应释放参与者名额，允许新玩家进入后续帧。 */
    @Test void markPolicyReleasesConsumedParticipantCapacity() {
        FrameMatchRuntime runtime = new FrameMatchRuntime("mark",
                new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.MARK, 8, 1),
                (frame, batch) -> { }, event -> { }, event -> { });
        runtime.submit(new FrameInput("first", 1, 1, 0, new byte[]{1}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        runtime.submit(new FrameInput("second", 1, 2, 0, new byte[]{2}, "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(2, runtime.frameNo());
    }

    @Test void failedSimulationDoesNotAdvanceFrameOrConsumeInput() {
        AtomicBoolean fail = new AtomicBoolean(true);
        FrameMatchRuntime runtime = new FrameMatchRuntime("retry", FrameMatchConfig.defaults(),
                (frame, batch) -> { if (fail.getAndSet(false)) throw new IllegalStateException("simulation"); },
                event -> { }, event -> { });
        FrameInput input = new FrameInput("u", 1, 1, 0, new byte[] {1}, "t");
        runtime.submit(input).toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
        assertEquals(0, runtime.frameNo());
        assertThrows(CompletionException.class, () -> runtime.submit(input).toCompletableFuture().join());
        assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
        assertEquals(0, runtime.frameNo());
        runtime.closeAsync().toCompletableFuture().join();
    }

    /** 重放输入使用当前 batch 的目标帧，提交 trace 来自 tick 调度上下文。 */
    @Test void repeatedInputIsRetargetedAndTickTraceIsAuthoritative() {
        List<FrameCommitted> committed = new ArrayList<>();
        FrameMatchRuntime runtime = new FrameMatchRuntime("repeat",
                new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.REPEAT_LAST, 8, 2),
                (frame, batch) -> { }, committed::add, event -> { });
        runtime.submit(new FrameInput("u", 1, 1, 0, new byte[]{1}, "input-trace")).toCompletableFuture().join();
        runtime.tick("tick-one").toCompletableFuture().join();
        runtime.tick("tick-two").toCompletableFuture().join();
        assertEquals(2, committed.getLast().batch().inputs().getFirst().targetFrame());
        assertEquals("tick-two", committed.getLast().traceId());
    }

    /** 活跃会话的旧序号即使超出旧窗口也不得在 BUFFER 中重新排队。 */
    @Test void inputSequenceWatermarkRejectsOldBufferedSequence() {
        List<FrameCommitted> committed = new ArrayList<>();
        FrameMatchRuntime runtime = new FrameMatchRuntime("watermark", new FrameMatchConfig(
                InputTimingPolicy.BUFFER, MissingInputPolicy.EMPTY, 8, 2), (frame, batch) -> { }, committed::add, event -> { });
        runtime.submit(new FrameInput("u", 2, 1, 0, new byte[]{1}, "trace")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        runtime.submit(new FrameInput("u", 1, 1, 0, new byte[]{2}, "trace")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(0, committed.getLast().batch().inputs().size());
    }

    /** byte[] 的记录相等性必须遵循内容，避免测试、缓存和去重出现引用语义。 */
    @Test void frameInputUsesPayloadValueEquality() {
        assertEquals(new FrameInput("u", 1, 1, 0, new byte[]{1}, "trace"),
                new FrameInput("u", 1, 1, 0, new byte[]{1}, "trace"));
    }
}
