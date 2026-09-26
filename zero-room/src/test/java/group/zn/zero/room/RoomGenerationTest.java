package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.ArrayDeque;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 同 ID 房间重建后不能接受前一代排队命令。 @author zn */
class RoomGenerationTest {
    /** 销毁结果回调重建房间，之前排队的 join 必须拒绝。 */
    @Test void oldQueuedCommandCannotEnterReplacementRoom() {
        var work = new ArrayDeque<Runnable>();
        var scheduler = new ExecutorActorScheduler(work::add);
        var service = new LocalRoomService(scheduler);
        var id = new RoomId("r");
        service.create(id, 2, 0);
        service.close(id);
        service.destroy(id).thenRun(() -> service.create(id, 2, 0));
        var oldJoin = service.join(id, "old-player").toCompletableFuture();
        while (!work.isEmpty()) work.remove().run();
        assertThrows(CompletionException.class, oldJoin::join);
        assertTrue(service.snapshot(id).members().isEmpty());
        scheduler.close();
    }
}
