package group.zn.zero.framesync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class FrameMatchRuntimeTest {
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
}
