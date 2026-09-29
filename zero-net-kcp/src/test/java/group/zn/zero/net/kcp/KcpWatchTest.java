package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 指标观察器变慢或关闭时仍有界，不能阻塞传输 IO 或残留采样。 @author zn */
@Timeout(10)
class KcpWatchTest {
    /** 慢观察器最多占一个任务，服务关闭不等待外部消费者。 */
    @Test void boundsSlowSamplingAndCancelsWithService() throws Exception {
        var release = new CompletableFuture<Void>();
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG)) {
            var started = new CompletableFuture<Void>();
            var submissions = new AtomicInteger();
            var watch = fixture.server.watch(Duration.ofMillis(100), task -> {
                submissions.incrementAndGet();
                fixture.workers.execute(task);
            }, snapshot -> {
                started.complete(null);
                release.join();
            });
            try {
                started.get(2, TimeUnit.SECONDS);
                new CompletableFuture<Void>().completeOnTimeout(null, 350, TimeUnit.MILLISECONDS).join();
                assertEquals(1, submissions.get());
                assertEquals(7, fixture.server.channel().eventLoop().submit(() -> 7).get(1, TimeUnit.SECONDS));
                fixture.server.stop();
                assertTrue(watch.closed());
                assertEquals(1, submissions.get());
            } finally { release.complete(null); }
        }
    }
    /** 已排队但尚未执行的采样，在订阅关闭后不再调用消费者。 */
    @Test void ignoresQueuedSampleAfterClose() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG)) {
            var queued = new LinkedBlockingQueue<Runnable>();
            var calls = new AtomicInteger();
            var watch = fixture.server.watch(Duration.ofMillis(100), queued::add, snapshot -> calls.incrementAndGet());
            var pending = queued.poll(2, TimeUnit.SECONDS);
            assertNotNull(pending);
            watch.close();
            pending.run();
            fixture.server.channel().eventLoop().submit(() -> { }).get(1, TimeUnit.SECONDS);
            assertEquals(0, calls.get());
            assertTrue(queued.isEmpty());
            assertEquals(0, fixture.server.failureCount());
        }
    }
}
