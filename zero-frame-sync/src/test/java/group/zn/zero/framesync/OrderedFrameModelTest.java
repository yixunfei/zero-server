package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 确定顺序、稀疏远期输入、历史 batch 及参与者预算验证。 @author zn */
class OrderedFrameModelTest {
    @Test void randomizedTicksMatchOrderedReferenceAndKeepOldBatches() {
        for (MissingInputPolicy policy : MissingInputPolicy.values()) exercise(policy);
    }

    private void exercise(final MissingInputPolicy policy) {
        List<FrameInputBatch> actualBatches = new ArrayList<>();
        List<List<FrameInput>> expectedBatches = new ArrayList<>();
        Map<String, Map<Long, FrameInput>> pending = new TreeMap<>();
        Map<String, FrameInput> last = new HashMap<>();
        var runtime = new FrameMatchRuntime("model", new FrameMatchConfig(InputTimingPolicy.REJECT, policy, 128, 4096),
                (frame, batch) -> actualBatches.add(batch), event -> { }, event -> { });
        var random = new Random(93);
        for (int step = 0; step < 3000; step++) {
            if (step % 3 != 0) {
                String uid = "u" + random.nextInt(64);
                long target = runtime.frameNo() + 1 + random.nextInt(8);
                FrameInput input = new FrameInput(uid, step, target, 0, new byte[] {(byte) step}, "trace");
                pending.computeIfAbsent(uid, ignored -> new HashMap<>()).put(target, input);
                runtime.submit(input).toCompletableFuture().join();
                runtime.submit(input).toCompletableFuture().join();
            } else {
                long next = runtime.frameNo() + 1;
                List<FrameInput> expected = new ArrayList<>();
                pending.forEach((uid, frames) -> {
                    FrameInput input = frames.remove(next);
                    if (input != null) { expected.add(input); last.put(uid, input); }
                    else if (policy == MissingInputPolicy.REPEAT_LAST && last.containsKey(uid)) expected.add(last.get(uid));
                });
                if (policy == MissingInputPolicy.EMPTY) { pending.values().removeIf(Map::isEmpty); last.clear(); }
                expectedBatches.add(List.copyOf(expected));
                runtime.tick().toCompletableFuture().join();
                assertEquals(expected, actualBatches.getLast().inputs());
            }
        }
        for (int i = 0; i < actualBatches.size(); i++) assertEquals(expectedBatches.get(i), actualBatches.get(i).inputs());
        runtime.submit(new FrameInput("far", 1, Long.MAX_VALUE, 0, new byte[0], "trace")).toCompletableFuture().join();
        runtime.close();
        assertThrows(CompletionException.class, () -> runtime.tick().toCompletableFuture().join());
    }

    @Test void repeatHistoryCannotGrowPastParticipantBudget() {
        var runtime = new FrameMatchRuntime("bounded", new FrameMatchConfig(InputTimingPolicy.REJECT,
                MissingInputPolicy.REPEAT_LAST, 128, 2), (frame, batch) -> { }, event -> { }, event -> { });
        runtime.submit(new FrameInput("a", 1, 1, 0, new byte[0], "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        runtime.submit(new FrameInput("b", 1, 2, 0, new byte[0], "t")).toCompletableFuture().join();
        runtime.tick().toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> runtime.submit(
                new FrameInput("c", 1, 3, 0, new byte[0], "t")).toCompletableFuture().join());
        runtime.submit(new FrameInput("a", 2, 3, 0, new byte[0], "t")).toCompletableFuture().join();
    }
}
