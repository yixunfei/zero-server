package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 共享 Actor 调度器下同名房间仍归属于各自服务；覆盖延迟执行和独立事件。 @author zn */
class RoomSharedSchedulerTest {
    @Test void sharedSchedulerKeepsStateAndEventsWithTheirService() {
        var tasks = new ArrayDeque<Runnable>();
        try (var scheduler = new ExecutorActorScheduler(tasks::add)) {
            List<RoomEvent> firstEvents = new ArrayList<>();
            List<RoomEvent> secondEvents = new ArrayList<>();
            var first = new LocalRoomService(scheduler, firstEvents::add);
            var second = new LocalRoomService(scheduler, secondEvents::add);
            var same = new RoomId("same");
            first.create(same, 2, 100);
            second.create(same, 2, 100);
            var a = first.join(same, "alice").toCompletableFuture();
            var b = second.join(same, "bob").toCompletableFuture();
            assertFalse(a.isDone());
            assertFalse(b.isDone());
            while (!tasks.isEmpty()) tasks.remove().run();
            a.join(); b.join();
            assertEquals("alice", first.snapshot(same).members().getFirst().playerId());
            assertEquals("bob", second.snapshot(same).members().getFirst().playerId());
            assertEquals(List.of(0L, 1L), firstEvents.stream().map(RoomEvent::sequence).toList());
            assertEquals("bob", secondEvents.getLast().playerId());
            var other = new RoomId("other");
            second.create(other, 1, 0);
            var joined = second.join(other, "carol").toCompletableFuture();
            while (!tasks.isEmpty()) tasks.remove().run();
            joined.join();
            assertEquals("carol", second.snapshot(other).members().getFirst().playerId());
            assertEquals(2, firstEvents.size());
        }
    }
}
