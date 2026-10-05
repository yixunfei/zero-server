package group.zn.zero.cache;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 验证同键/总写入预算、取消隔离与大量同步完成时的无递归推进。 @author zn */
@Timeout(10)
class CacheWriteAdmissionTest {
    /** 排满同键预算后取消观察不能释放容量；1024 项同步完成链不得耗尽栈或悬挂尾部。 */
    @Test void fullKeyQueueDrainsWithoutRecursionAndCancellationCannotReleaseCapacity() {
        var operations = new CacheKeyOperations<String>();
        var gate = new CompletableFuture<Integer>();
        var first = operations.mutate("key", token -> gate).toCompletableFuture();
        var completed = new AtomicInteger();
        List<CompletableFuture<Integer>> queued = new ArrayList<>();
        for (int i = 1; i < 1024; i++) {
            queued.add(operations.mutate("key", token ->
                    CompletableFuture.completedFuture(completed.incrementAndGet())).toCompletableFuture());
        }
        assertTrue(first.cancel(false));
        assertRejected(() -> operations.mutate("key", token -> CompletableFuture.completedFuture(-1)));
        var last = queued.getLast().thenCompose(value ->
                operations.mutate("key", token -> CompletableFuture.completedFuture(value + 1)));
        gate.complete(0);
        assertEquals(1024, last.join());
        assertEquals(1023, completed.get());
        assertTrue(queued.stream().allMatch(CompletableFuture::isDone));
    }

    /** 多个键共同耗尽总预算，物理失败后回收容量，拒绝不遗留新键状态。 */
    @Test void totalQueueBudgetIsReclaimedOnlyAfterPhysicalCompletion() {
        var operations = new CacheKeyOperations<String>();
        assertEquals(7, operations.mutate("callback-then-throw", token -> new CallbackThenThrowFuture())
                .toCompletableFuture().join());
        List<CompletableFuture<Integer>> gates = new ArrayList<>();
        for (int i = 0; i < 4096; i++) {
            var gate = new CompletableFuture<Integer>();
            gates.add(gate);
            operations.mutate("key-" + i, token -> gate);
        }
        assertRejected(() -> operations.mutate("overflow", token -> CompletableFuture.completedFuture(1)));
        gates.getFirst().completeExceptionally(new IllegalStateException("backend unavailable"));
        var accepted = new CompletableFuture<Integer>();
        var next = operations.mutate("overflow", token -> accepted).toCompletableFuture();
        assertFalse(next.isDone());
        assertRejected(() -> operations.mutate("another", token -> CompletableFuture.completedFuture(2)));
        accepted.complete(3);
        assertEquals(3, next.join());
        for (var gate : gates) gate.complete(0);
        assertThrows(CompletionException.class, () -> operations.mutate("failure", token -> {
            throw new AssertionError("synchronous failure");
        }).toCompletableFuture().join());
        assertEquals(4, operations.mutate("new", token -> CompletableFuture.completedFuture(4))
                .toCompletableFuture().join());
    }

    private static void assertRejected(final Runnable operation) {
        assertEquals(CacheErrorCode.BACKPRESSURE_REJECTED,
                assertThrows(ZeroException.class, operation::run).errorCode());
    }

    /** 故意违反正常 future 约定的 SPI 阶段：同步通知完成后，注册过程又抛错。 */
    private static final class CallbackThenThrowFuture extends CompletableFuture<Integer> {
        /** @return 本实现总是抛出注册异常；先前完成通知仍然只应结算一次。 */
        @Override public CompletableFuture<Integer> whenComplete(
                final java.util.function.BiConsumer<? super Integer, ? super Throwable> action) {
            action.accept(7, null);
            throw new IllegalStateException("registration failed after callback");
        }
    }
}
