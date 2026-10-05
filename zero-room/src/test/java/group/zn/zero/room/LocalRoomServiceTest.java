package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LocalRoomServiceTest {
    @Test
    void lifecycleAndIdempotentSettlement() {
        LocalRoomService service = new LocalRoomService();
        RoomId id = new RoomId("r1");
        service.create(id, 2, 1000);
        service.join(id, "a").toCompletableFuture().join();
        service.join(id, "b").toCompletableFuture().join();
        service.ready(id, "a").toCompletableFuture().join();
        service.ready(id, "b").toCompletableFuture().join();
        service.start(id).toCompletableFuture().join();
        Settlement first = service.settle(id, "s1", "win").toCompletableFuture().join();
        assertEquals(first, service.settle(id, "s1", "win").toCompletableFuture().join());
        assertEquals(RoomState.SETTLED, service.snapshot(id).state());
        assertEquals(1, service.stats(id).settlements());
    }

    @Test
    void capacityAndDuplicateJoinAreSafe() {
        LocalRoomService service = new LocalRoomService(); RoomId id = new RoomId("r"); service.create(id, 1, 100);
        service.join(id, "a").toCompletableFuture().join(); service.join(id, "a").toCompletableFuture().join();
        assertThrows(CompletionException.class, () -> service.join(id, "b").toCompletableFuture().join());
        assertEquals(1, service.snapshot(id).members().size());
    }

    @Test
    void snapshotsAreImmutableAndJoinRunsOnRoomLane() {
        LocalRoomService service = new LocalRoomService(); RoomId id = new RoomId("r"); service.create(id, 2, 100);
        service.join(id, "a").toCompletableFuture().join(); RoomSnapshot snapshot = service.snapshot(id);
        assertThrows(UnsupportedOperationException.class, () -> snapshot.members().clear());
        assertEquals(1, snapshot.sequence());
    }

    /** 创建提交与首个 join 线性化，首条房间事件始终先于加入事件。 */
    @Test
    void concurrentCreateAndJoinPreserveCreatedBeforeJoined() throws Exception {
        List<RoomEvent.Type> events = new ArrayList<>();
        LocalRoomService service = new LocalRoomService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> events.add(event.type()));
        RoomId id = new RoomId("ordered-create");
        CountDownLatch started = new CountDownLatch(1);
        Thread creator = new Thread(() -> {
            started.countDown();
            service.create(id, 1, 0);
        });
        Thread joiner = new Thread(() -> {
            try {
                started.await(1, TimeUnit.SECONDS);
                service.join(id, "p").toCompletableFuture().join();
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
        });
        creator.start();
        joiner.start();
        creator.join(2000);
        joiner.join(2000);
        assertEquals(List.of(RoomEvent.Type.CREATED, RoomEvent.Type.JOINED), events);
    }
}
