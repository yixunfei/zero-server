package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ServerDrainOrchestratorTest {
    @Test
    void drainsInFlightAndStopsAdmission() throws Exception {
        AtomicInteger stopped = new AtomicInteger();
        ServerDrainOrchestrator coordinator = new ServerDrainOrchestrator(stopped::incrementAndGet, () -> { });
        coordinator.start();
        ServerDrainOrchestrator.RequestLease lease = coordinator.tryAcquire();
        assertNotNull(lease);
        CountDownLatch done = new CountDownLatch(1);
        Thread drain = new Thread(() -> {
            assertTrue(coordinator.drain(Duration.ofSeconds(2)));
            done.countDown();
        });
        drain.start();
        assertTrue(waitForState(coordinator, ServerDrainState.DRAINING));
        assertNull(coordinator.tryAcquire());
        lease.close();
        assertTrue(done.await(2, TimeUnit.SECONDS));
        assertEquals(ServerDrainState.STOPPED, coordinator.state());
        assertEquals(1, stopped.get());
    }

    @Test
    void timeoutInvokesForceCloseAndReportsStopped() {
        AtomicInteger forced = new AtomicInteger();
        ServerDrainOrchestrator coordinator = new ServerDrainOrchestrator(() -> { }, forced::incrementAndGet);
        coordinator.start();
        ServerDrainOrchestrator.RequestLease lease = coordinator.tryAcquire();
        assertFalse(coordinator.drain(Duration.ZERO));
        assertEquals(1, forced.get());
        assertEquals(ServerDrainState.STOPPED, coordinator.state());
        assertEquals(1, coordinator.healthSnapshot().inFlight());
        lease.close();
    }

    @Test
    void initialAndRunningHealthAreExplicit() {
        ServerDrainOrchestrator coordinator = new ServerDrainOrchestrator(() -> { }, () -> { });
        assertEquals(ServerDrainState.NEW, coordinator.healthSnapshot().state());
        coordinator.start();
        assertTrue(coordinator.healthSnapshot().healthy());
        assertTrue(coordinator.healthSnapshot().accepting());
    }

    private static boolean waitForState(ServerDrainOrchestrator coordinator, ServerDrainState expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (coordinator.state() != expected && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return coordinator.state() == expected;
    }
}
