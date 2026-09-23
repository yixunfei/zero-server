package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.Test;

/** 离开成员的资源释放与开局回归。 @author zn */
class RoomReportAuditTest {
    /** 历史离开成员不应占据快照，也不能阻断剩余玩家开局。 */
    @Test void leavingMembersAreReleasedAndDoNotBlockStart() {
        LocalRoomService service = new LocalRoomService();
        RoomId id = new RoomId("r");
        service.create(id, 2, 100);
        for (int i = 0; i < 200; i++) {
            service.join(id, "p" + i).toCompletableFuture().join();
            service.leave(id, "p" + i).toCompletableFuture().join();
        }
        assertEquals(0, service.snapshot(id).members().size());
        service.join(id, "remaining").toCompletableFuture().join();
        service.ready(id, "remaining").toCompletableFuture().join();
        service.start(id).toCompletableFuture().join();
        assertEquals(RoomState.RUNNING, service.snapshot(id).state());
    }
    /** 关闭房间可显式释放；历史仅保留最近 1024 条。 */
    @Test void closedRoomCanBeDestroyedAndHistoryIsBounded() {
        LocalRoomService service = new LocalRoomService();
        RoomId id = new RoomId("r");
        service.create(id, 1, 0);
        for (int i = 0; i < 600; i++) {
            service.join(id, "p" + i).toCompletableFuture().join();
            service.leave(id, "p" + i).toCompletableFuture().join();
        }
        assertEquals(1024, service.events(id).size());
        assertEquals(177, service.droppedEventCount(id));
        service.close(id).toCompletableFuture().join();
        service.destroy(id).toCompletableFuture().join();
        assertThrows(IllegalStateException.class, () -> service.snapshot(id));
    }
    /** 房间回调错误不能静默丢失。 */
    @Test void eventConsumerFailureIsReported() {
        LocalRoomService service = new LocalRoomService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> { throw new IllegalStateException("event delivery failed"); });
        assertThrows(group.zn.zero.core.error.ZeroException.class, () -> service.create(new RoomId("r"), 1, 0));
    }

}
