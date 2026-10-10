package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletableFuture;
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
        List<RoomEvent.Type> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch published = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        LocalRoomService service = new LocalRoomService(new group.zn.zero.actor.scheduler.LocalActorScheduler(),
                event -> {
                    events.add(event.type());
                    if (event.type() == RoomEvent.Type.CREATED) {
                        published.countDown();
                        try { assertTrue(release.await(2, TimeUnit.SECONDS)); }
                        catch (InterruptedException failure) {
                            Thread.currentThread().interrupt();
                            throw new AssertionError(failure);
                        }
                    }
                });
        RoomId id = new RoomId("ordered-create");
        CompletableFuture<Void> creator = CompletableFuture.runAsync(() -> service.create(id, 1, 0));
        try {
            assertTrue(published.await(1, TimeUnit.SECONDS));
            var joined = service.join(id, "p").toCompletableFuture();
            assertFalse(joined.isDone());
            release.countDown();
            joined.get(2, TimeUnit.SECONDS);
            creator.get(2, TimeUnit.SECONDS);
        } finally { release.countDown(); }
        assertEquals(List.of(RoomEvent.Type.CREATED, RoomEvent.Type.JOINED), events);
    }
}
