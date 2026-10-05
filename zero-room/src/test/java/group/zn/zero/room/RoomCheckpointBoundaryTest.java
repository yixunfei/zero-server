package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** 提交快照代替预先复制 checkpoint 后，失败仍须恢复所有业务字段。 @author zn */
class RoomCheckpointBoundaryTest {
    /** 已修改成员和统计但事件还未提交时失败，历史、淘汰数及后续序号均保持一致。 */
    @Test void preCommitFailureRestoresStateWithoutChangingFullHistory() throws Exception {
        var received = new ArrayList<RoomEvent>();
        var service = new LocalRoomService(new LocalActorScheduler(), received::add, 2);
        var id = new RoomId("checkpoint");
        service.create(id, 2, 0);
        service.join(id, "old").toCompletableFuture().join();
        service.leave(id, "old").toCompletableFuture().join();
        var snapshot = service.snapshot(id);
        var stats = service.stats(id);
        var history = service.eventHistory(id, -1);
        int notifications = received.size();

        exhaustSequence(service, id);
        assertThrows(CompletionException.class, () -> service.join(id, "candidate").toCompletableFuture().join());
        assertEquals(snapshot, service.snapshot(id));
        assertEquals(stats, service.stats(id));
        assertEquals(history, service.eventHistory(id, -1));
        assertEquals(notifications, received.size());

        service.join(id, "candidate").toCompletableFuture().join();
        assertEquals(snapshot.sequence() + 1, service.snapshot(id).sequence());
        assertEquals(stats.joins() + 1, service.stats(id).joins());
        assertEquals(history.droppedEvents() + 1, service.droppedEventCount(id));
        assertEquals(1, service.snapshot(id).members().size());
    }

    private static void exhaustSequence(final LocalRoomService service, final RoomId id) throws Exception {
        // 构造事件序号溢出的确定性故障，无需执行 Long.MAX_VALUE 次命令。
        Field directory = LocalRoomService.class.getDeclaredField("rooms");
        directory.setAccessible(true);
        Object room = ((Map<?, ?>) directory.get(service)).get(id);
        Field sequence = room.getClass().getDeclaredField("seq");
        sequence.setAccessible(true);
        sequence.setLong(room, Long.MAX_VALUE);
    }
}
