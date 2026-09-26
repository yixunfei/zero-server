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

    @Test void failedSimulationDoesNotAdvanceFrameOrConsumeInput() {
        AtomicBoolean fail = new AtomicBoolean(true);
        FrameMatchRuntime runtime = new FrameMatchRuntime("retry", FrameMatchConfig.defaults(),
                (frame, batch) -> { if (fail.getAndSet(false)) throw new IllegalStateException("simulation"); },
                event -> { }, event -> { });
        FrameInput input = new FrameInput("u", 1, 1, 0, new byte[] {1}, "t");
        runtime.submit(input).toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
        assertEquals(0, runtime.frameNo());
        runtime.submit(input).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertEquals(1, runtime.frameNo());
    }
}
