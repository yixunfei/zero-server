package group.zn.zero.room;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 房间消费者可以等待另一线程操作其他房间，目录锁不得跨用户代码。 @author zn */
class RoomCallbackLockTest {
    /** 创建和 join 两条路径都需要锁外调用用户观察者。 */
    @Test void callbacksCanWaitForAnotherRoomMutation() {
        var reference = new AtomicReference<LocalRoomService>();
        var other = new RoomId("other");
        var service = new LocalRoomService(new LocalActorScheduler(), event -> {
            if (!event.roomId().equals(other)) {
                var completed = new CompletableFuture<Void>();
                Thread.ofVirtual().start(() -> {
                    try { reference.get().join(other, "p").toCompletableFuture().join(); completed.complete(null); }
                    catch (Throwable failure) { completed.completeExceptionally(failure); }
                });
                try { completed.get(1, TimeUnit.SECONDS); }
                catch (Exception failure) { throw new IllegalStateException("callback holds directory lock", failure); }
            }
        });
        reference.set(service);
        service.create(other, 2, 0);
        var id = new RoomId("first");
        service.create(id, 2, 0);
        service.join(id, "p").toCompletableFuture().join();
        assertEquals(1, service.snapshot(other).members().size());
    }
}
