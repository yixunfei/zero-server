package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import group.zn.zero.core.error.ZeroException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 房间提交与外部事件副作用不可分叉。@author zn */
class RoomCommitBoundaryTest {
    /** 加入、开局和结算事件已被下游接收时，通知失败不得撤销本地提交。 */
    @Test void deliveryFailurePreservesJoinStartAndSettlement() {
        List<RoomEvent> received = new ArrayList<>();
        var failingType = new AtomicReference<>(RoomEvent.Type.JOINED);
        var cause = new IllegalStateException("downstream notified then failed");
        var service = new LocalRoomService(new LocalActorScheduler(), event -> {
            received.add(event);
            if (event.type() == failingType.get()) throw cause;
        });
        RoomId id = new RoomId("commit");
        service.create(id, 1, 0);
        var error = assertThrows(CompletionException.class, () -> service.join(id, "p").toCompletableFuture().join());
        assertSame(cause, ((ZeroException) error.getCause()).getCause());
        assertEquals(1, service.snapshot(id).members().size());
        assertEquals(1, service.stats(id).joins());
        service.join(id, "p").toCompletableFuture().join();
        assertEquals(2, received.size());
        service.ready(id, "p").toCompletableFuture().join();
        failingType.set(RoomEvent.Type.STARTED);
        assertThrows(CompletionException.class, () -> service.start(id).toCompletableFuture().join());
        assertEquals(RoomState.RUNNING, service.snapshot(id).state());
        assertEquals(PlayerSlot.PLAYING, service.snapshot(id).members().getFirst().slot());
        failingType.set(RoomEvent.Type.SETTLED);
        assertThrows(CompletionException.class, () -> service.settle(id, "key", "win").toCompletableFuture().join());
        assertEquals(RoomState.SETTLED, service.snapshot(id).state());
        assertEquals(new Settlement("key", "win"), service.settle(id, "key", "win").toCompletableFuture().join());
        assertEquals(received, service.events(id));
        assertEquals(4, service.snapshot(id).sequence());
        assertEquals(1, service.stats(id).settlements());
    }

    /** 容量只影响本地历史，不丢弃实时通知；非法容量必须拒绝。 */
    @Test void historyBudgetDoesNotLimitLiveDelivery() {
        List<RoomEvent> received = new ArrayList<>();
        var service = new LocalRoomService(new LocalActorScheduler(), received::add, 2);
        RoomId id = new RoomId("budget");
        service.create(id, 1, 0);
        service.join(id, "p").toCompletableFuture().join();
        service.leave(id, "p").toCompletableFuture().join();
        assertEquals(3, received.size());
        assertEquals(received.subList(1, 3), service.events(id));
        assertEquals(1, service.droppedEventCount(id));
        var history = service.eventHistory(id, -1);
        org.junit.jupiter.api.Assertions.assertTrue(history.gap());
        assertEquals(1, history.firstAvailableSequence());
        assertEquals(2, history.latestSequence());
        assertEquals(service.events(id), history.events());
        org.junit.jupiter.api.Assertions.assertFalse(service.eventHistory(id, 0).gap());
        org.junit.jupiter.api.Assertions.assertTrue(service.eventHistory(id, 2).events().isEmpty());
        assertThrows(UnsupportedOperationException.class, () -> history.events().clear());
        assertThrows(IllegalArgumentException.class,
                () -> new LocalRoomService(new LocalActorScheduler(), received::add, 0));
    }
}
