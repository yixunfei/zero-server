package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** 通过受控关闭回调验证 drain 的并发完成及重入边界。 */
class ServerDrainConcurrencyTest {
    @Test
    void concurrentTimeoutDoesNotRepeatForceCloseOrPublishStoppedEarly() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ServerDrainOrchestrator coordinator = new ServerDrainOrchestrator(() -> { }, () -> {
            if (calls.incrementAndGet() == 1) {
                entered.countDown();
                await(release);
            }
        });
        coordinator.start();
        try (var lease = coordinator.tryAcquire()) {
            CompletableFuture<Boolean> first = CompletableFuture.supplyAsync(() -> coordinator.drain(Duration.ZERO));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                assertFalse(coordinator.drain(Duration.ZERO));
                assertEquals(1, calls.get());
                assertEquals(ServerDrainState.DRAINING, coordinator.state());
                lease.close();
                assertFalse(coordinator.drain(Duration.ZERO));
                assertEquals(ServerDrainState.DRAINING, coordinator.state());
            } finally {
                release.countDown();
            }
            assertFalse(first.get(5, TimeUnit.SECONDS));
            assertEquals(ServerDrainState.STOPPED, coordinator.state());
            assertFalse(coordinator.drain(Duration.ZERO));
        }
    }

    @Test
    void forceCloseMayReenterDrainWithoutRecursion() {
        AtomicInteger calls = new AtomicInteger();
        ServerDrainOrchestrator[] holder = new ServerDrainOrchestrator[1];
        holder[0] = new ServerDrainOrchestrator(() -> { }, () -> {
            if (calls.incrementAndGet() == 1) {
                assertFalse(holder[0].drain(Duration.ZERO));
            }
        });
        holder[0].start();
        try (var lease = holder[0].tryAcquire()) {
            assertFalse(holder[0].drain(Duration.ZERO));
            assertEquals(1, calls.get());
        }
    }

    private static void await(final CountDownLatch latch) {
        try {
            assertTrue(latch.await(5, TimeUnit.SECONDS));
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }
}
