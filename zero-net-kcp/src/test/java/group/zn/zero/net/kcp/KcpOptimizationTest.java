package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.protocol.ProtocolFrame;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 热路径优化必须保持帧存储所有权、心跳和关闭代际安全。 @author zn */
@Timeout(20)
class KcpOptimizationTest {
    /** 一帧完全确认后即回收自身预算，不等待后续帧队列全部排空。 */
    @Test void releasesWholeFrameWhileLaterFrameRemainsPending() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), KcpServerTest.CONFIG)) {
            peer.raw(peer.heartbeat());
            var connection = fixture.opened.get(2, TimeUnit.SECONDS);
            peer.receiveLimit = 1;
            connection.sendFrame(KcpServerTest.frame(1)).toCompletableFuture().join();
            connection.sendFrame(new ProtocolFrame(2, 1, 0, null, new byte[3000])).toCompletableFuture().join();
            long held = fixture.server.pendingSendBytes();
            peer.until(() -> peer.received.size() == 1);
            peer.engine.flush(true, System.currentTimeMillis());
            peer.until(() -> fixture.server.pendingSendBytes() < held);
            assertTrue(fixture.server.pendingSendBytes() > 0);
            connection.close().toCompletableFuture().join();
            assertEquals(0, fixture.server.pendingSendBytes());
        }
    }
    /** 客户端自动心跳保持空闲连接；服务端无待发数据时不更新算法。 */
    @Test void heartbeatsKeepIdleSessionWithoutAlgorithmPolling() throws Exception {
        var config = KcpOptions.defaults().toBuilder().timeouts(new KcpTimeouts(Duration.ofMillis(300),
                Duration.ofMillis(100), Duration.ofMillis(500), Duration.ofSeconds(1), Duration.ofMinutes(1))).build();
        try (var fixture = new KcpServerTest.Fixture(config)) {
            var info = fixture.server.issueConnectInfo(new KcpTestControl(), "127.0.0.1", fixture.server.boundPort()).toCompletableFuture().join();
            var client = new KcpClient(info, fixture.workers, (connection, frame) -> CompletableFuture.completedFuture(List.of()),
                    new ConnectionListener() { }, null);
            try {
                client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                new CompletableFuture<Void>().completeOnTimeout(null, 900, TimeUnit.MILLISECONDS).join();
                assertEquals(KcpClient.State.CONNECTED, client.state());
                assertEquals(1, fixture.server.snapshot().connected());
                assertTrue(fixture.server.snapshot().receivedDatagrams() >= 4);
                assertEquals(0, fixture.server.snapshot().updates());
            } finally { client.close().toCompletableFuture().join(); }
        }
    }
    /** 等待控制面时关闭，不允许迟到票据创建新 socket，并撤销迟到授权。 */
    @Test void revokesTicketArrivingAfterRecoveryClosed() throws Exception {
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor()) {
            var acquired = new CompletableFuture<KcpConnectInfo>();
            var revoked = new java.util.concurrent.atomic.AtomicInteger();
            var plane = new KcpControlPlane() {
                @Override public java.util.concurrent.CompletionStage<KcpConnectInfo> acquire() { return acquired; }
                @Override public java.util.concurrent.CompletionStage<Void> revoke(final int conv) {
                    revoked.incrementAndGet(); return CompletableFuture.completedFuture(null);
                }
            };
            var recovery = new KcpRecovery(plane, workers, (connection, frame) -> CompletableFuture.completedFuture(List.of()),
                    new ConnectionListener() { }, null);
            var pending = recovery.reconnect(); recovery.close().toCompletableFuture().join();
            acquired.complete(new KcpConnectInfo("127.0.0.1", 9001,
                    new KcpTicket(7, new byte[32], java.time.Instant.now().plusSeconds(30)), KcpOptions.defaults()));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> pending.toCompletableFuture().get(2, TimeUnit.SECONDS));
            assertEquals(1, revoked.get()); assertEquals(KcpRecovery.State.CLOSED, recovery.state());
        }
    }
    /** 当前实现认证路径的线程分配测量；不是整个网络栈的 B/op，也不对机器噪声设阈值。 */
    @Test void measuresAuthenticationAllocation() {
        var bean = java.lang.management.ManagementFactory.getThreadMXBean();
        if (!(bean instanceof com.sun.management.ThreadMXBean allocation) || !allocation.isThreadAllocatedMemorySupported()) return;
        allocation.setThreadAllocatedMemoryEnabled(true);
        var ticket = new KcpTicket(7, new byte[32], java.time.Instant.now().plusSeconds(30));
        var encoder = new KcpDatagramCodec(ticket, true); var decoder = new KcpDatagramCodec(ticket, false);
        var body = Unpooled.wrappedBuffer(new byte[512]);
        try {
            for (int round = 0; round < 4; round++) {
                long before = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId());
                for (int i = 0; i < 5000; i++) {
                    var packet = encoder.encode(UnpooledByteBufAllocator.DEFAULT, body);
                    var decoded = decoder.decode(packet);
                    try { assertEquals(512, decoded.readableBytes()); } finally { decoded.release(); packet.release(); }
                }
                long bytes = allocation.getThreadAllocatedBytes(Thread.currentThread().threadId()) - before;
                System.out.printf(java.util.Locale.ROOT, "KCP_AUTH_ALLOC round=%d encode_decode_ops=5000 bytes_per_op=%.2f%n", round, bytes / 5000.0);
            }
        } finally { body.release(); }
    }
}
