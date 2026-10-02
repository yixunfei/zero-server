package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 查询必须看到完整提交，而非并发成员表和普通字段的混合。 @author zn */
class RoomSnapshotPublicationTest {
    /** 成员数、事件序号来自同一次提交；保留的旧快照不随修改变化。 */
    @Test void readersObserveCompleteImmutableSnapshots() throws Exception {
        try (var executor = Executors.newSingleThreadExecutor();
                var scheduler = new ExecutorActorScheduler(executor)) {
            var service = new LocalRoomService(scheduler);
            var id = new RoomId("r");
            var initial = service.create(id, 500, 0);
            var writer = java.util.concurrent.CompletableFuture.runAsync(() -> {
                for (int i = 0; i < 500; i++) service.join(id, "p" + i).toCompletableFuture().join();
            });
            do {
                var view = service.snapshot(id);
                assertEquals(view.sequence(), view.members().size());
            } while (!writer.isDone());
            writer.get(3, TimeUnit.SECONDS);
            assertEquals(500, service.stats(id).joins());
            assertEquals(0, initial.members().size());
            assertEquals(500, service.snapshot(id).sequence());
        }
    }

    /** 已交付事件的失败不得回滚发布值；校验失败也不能发布半完成状态。 */
    @Test void publishedSnapshotSurvivesObserverFailure() {
        var reference = new AtomicReference<LocalRoomService>();
        var service = new LocalRoomService(new LocalActorScheduler(), event -> {
            if (event.type() == RoomEvent.Type.JOINED) {
                assertEquals(1, reference.get().snapshot(event.roomId()).members().size());
                throw new IllegalStateException("observer failed");
            }
        });
        reference.set(service);
        var id = new RoomId("r");
        service.create(id, 1, 0);
        assertThrows(RuntimeException.class, () -> service.join(id, "p").toCompletableFuture().join());
        assertThrows(RuntimeException.class, () -> service.join(id, "q").toCompletableFuture().join());
        assertEquals(1, service.snapshot(id).sequence());
        assertEquals(1, service.stats(id).joins());
    }
}
