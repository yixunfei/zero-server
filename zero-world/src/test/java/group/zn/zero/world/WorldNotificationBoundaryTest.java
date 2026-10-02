package group.zn.zero.world;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.actor.scheduler.LocalActorScheduler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** 外部观察者不得持有世界状态锁，通知失败不得撤销已提交状态。 @author zn */
class WorldNotificationBoundaryTest {
    /** 回调等待其他线程查询时，查询必须能立即看到已提交状态。 */
    @Test void callbackCanWaitForCrossThreadQuery() {
        var reference = new AtomicReference<LocalWorldService>();
        var service = new LocalWorldService(new LocalActorScheduler(), event -> {
            var query = new CompletableFuture<EntitySnapshot>();
            Thread.ofVirtual().start(() -> query.complete(reference.get().snapshot(event.entityId())));
            try {
                assertEquals(event.entityId(), query.get(2, TimeUnit.SECONDS).entityId());
            } catch (Exception failure) {
                throw new IllegalStateException("callback retained the world state lock", failure);
            }
        }, WorldMetrics.NOOP);
        reference.set(service);
        service.createWorld("w", "a");
        service.enterWorldAsync("e", "w").toCompletableFuture().join();
    }

    /** 通知失败仍向调用方报告，状态和历史已提交。 */
    @Test void observerFailureKeepsCommittedState() {
        var service = new LocalWorldService(new LocalActorScheduler(), event -> {
            throw new IllegalStateException("observer unavailable");
        }, WorldMetrics.NOOP);
        service.createWorld("w", "a");
        assertThrows(RuntimeException.class, () -> service.enterWorldAsync("e", "w").toCompletableFuture().join());
        assertEquals("a", service.snapshot("e").ownerShardId());
        assertEquals(1, service.events().size());
    }
}
