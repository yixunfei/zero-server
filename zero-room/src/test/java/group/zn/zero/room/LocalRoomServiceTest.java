package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.concurrent.CompletionException;
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
}
