package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 创建屏障有界准入、FIFO 转交与容量恢复测试。 @author zn */
class RoomCommandAdmissionTest {
    /** 创建未结束时拒绝超额提交，已接收任务顺序转交且不等待其完成。 */
    @Test void pendingCommandsAreBoundedAndHandedOffWithoutWaiting() {
        var admission = new RoomCommandAdmission();
        var order = new ArrayList<Integer>();
        var completions = new ArrayList<CompletableFuture<Void>>();
        var work = new CompletableFuture<Void>();
        for (int index = 0; index < RoomCommandAdmission.CAPACITY; index++) {
            int value = index;
            completions.add(admission.submit(() -> { order.add(value); return work; }).toCompletableFuture());
        }
        var rejection = assertThrows(CompletionException.class, () -> admission.submit(() -> work)
                .toCompletableFuture().join());
        assertInstanceOf(ZeroException.class, rejection.getCause());
        assertTrue(order.isEmpty());
        admission.start();
        assertEquals(java.util.stream.IntStream.range(0, RoomCommandAdmission.CAPACITY).boxed().toList(), order);
        assertFalse(completions.getFirst().isDone());
        admission.submit(() -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();
        work.complete(null);
        assertTrue(completions.stream().allMatch(CompletableFuture::isDone));
    }
}
