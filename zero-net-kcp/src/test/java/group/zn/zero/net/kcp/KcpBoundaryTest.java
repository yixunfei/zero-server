package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.buffer.ByteBufUtil;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 失效、背压、异步关闭、坏包与执行器边界验证。 @author zn */
@Timeout(20)
class KcpBoundaryTest {
    /** 空闲和绝对过期都关闭活跃会话；心跳不能延长授权有效期。 */
    @Test void expiresIdleAndAbsoluteLifetime() throws Exception {
        for (boolean absolute : new boolean[]{false, true}) {
            KcpOptions options = KcpTestConfig.create(1200, 10, 128, 8, 8, 512,
                    Duration.ofMillis(absolute ? 400 : 5000), Duration.ofMillis(300), 1_000_000, true);
            try (var fixture = new KcpServerTest.Fixture(options);
                    var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), options)) {
                peer.raw(peer.heartbeat());
                peer.until(() -> fixture.opened.isDone());
                KcpConnection connection = fixture.opened.join();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (!connection.isClosed() && System.nanoTime() < deadline) {
                    if (absolute) peer.raw(peer.heartbeat());
                    peer.pump();
                }
                assertTrue(connection.isClosed());
                assertEquals(1, fixture.server.failureCount());
            }
        }
    }
    /** 已认证但非法 KCP segment 拒绝，不允许利用分片长度驱动无界分配。 */
    @Test void rejectsMalformedSegmentsBeforeAlgorithmMutation() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG)) {
            KcpTicket ticket = fixture.ticket(new KcpTestControl());
            try (var peer = new KcpTestPeer(ticket, fixture.server.boundPort(), KcpServerTest.CONFIG)) {
                ByteBuf malformed = Unpooled.buffer(24).writeIntLE(ticket.conv()).writeByte(81).writeByte(255)
                        .writeShortLE(128).writeIntLE(0).writeIntLE(0).writeIntLE(0).writeIntLE(Integer.MAX_VALUE);
                ByteBuf packet = peer.wire.encode(UnpooledByteBufAllocator.DEFAULT, malformed);
                try { peer.raw(ByteBufUtil.getBytes(packet)); }
                finally { packet.release(); malformed.release(); }
                peer.until(() -> fixture.server.failureCount() == 1);
                assertEquals(0, fixture.calls.get());
                assertTrue(!fixture.opened.isDone());
            }
        }
    }
    /** 异步 handler 未完成时受帧数限制；关闭后仍保留正在执行的内存预算，直到回调完成。 */
    @Test void boundsInboundQueueAndCompletesCloseAfterAsyncHandler() throws Exception {
        KcpOptions options = KcpTestConfig.create(1200, 10, 128, 8, 1, 512,
                Duration.ofSeconds(30), Duration.ofSeconds(5), 10_000, true);
        CompletableFuture<List<ProtocolFrame>> response = new CompletableFuture<>();
        CompletableFuture<Void> entered = new CompletableFuture<>();
        CompletableFuture<Void> closed = new CompletableFuture<>();
        AtomicInteger calls = new AtomicInteger();
        try (var workers = Executors.newFixedThreadPool(2)) {
            var server = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), options,
                    new ZeroBinaryFrameCodec(), (connection, frame) -> {
                        calls.incrementAndGet(); entered.complete(null); return response;
                    }, new ConnectionListener() {
                        @Override public void onClose(final IConnection connection) { closed.complete(null); }
                    }, workers, null);
            try {
                server.start();
                KcpTicket ticket = server.issueTicket(new KcpTestControl()).toCompletableFuture().join();
                try (var peer = new KcpTestPeer(ticket, server.boundPort(), options)) {
                    peer.send(KcpServerTest.frame(1));
                    peer.until(entered::isDone);
                    peer.send(KcpServerTest.frame(2));
                    peer.until(() -> server.failureCount() > 0);
                    assertEquals(1, calls.get());
                    server.stop();
                    assertTrue(server.pendingInboundBytes() > 0);
                    response.complete(List.of(KcpServerTest.frame(3)));
                    closed.get(3, TimeUnit.SECONDS);
                    // onClose 完成之后同一调度器释放最后生命周期预算。
                    long limit = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    while (server.pendingInboundBytes() != 0 && System.nanoTime() < limit) Thread.onSpinWait();
                    assertEquals(0, server.pendingInboundBytes());
                    assertEquals(0, server.pendingSendBytes());
                }
            } finally { response.complete(List.of()); server.stop(); }
        }
    }
    /** 错误的直接执行器必须拒绝，业务不可运行在 IO 线程。 */
    @Test void rejectsInlineBusinessExecutor() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        var server = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), KcpServerTest.CONFIG,
                new ZeroBinaryFrameCodec(), (connection, frame) -> {
                    calls.incrementAndGet(); return CompletableFuture.completedFuture(List.of());
                }, new ConnectionListener() { }, Runnable::run, null);
        try {
            server.start();
            KcpTicket ticket = server.issueTicket(new KcpTestControl()).toCompletableFuture().join();
            try (var peer = new KcpTestPeer(ticket, server.boundPort(), KcpServerTest.CONFIG)) {
                peer.send(KcpServerTest.frame(1));
                peer.until(() -> server.failureCount() > 0);
                assertEquals(0, calls.get());
            }
        } finally { server.stop(); }
    }
    /** 中断绑定保留中断状态，回收 socket 且不关闭借用 IO。 */
    @Test void interruptedBindRollsBack() {
        ServerOptions options = ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1);
        try (var io = NettyIoResources.open(options)) {
            var server = new KcpServer(options, KcpServerTest.CONFIG, new ZeroBinaryFrameCodec(),
                    (connection, frame) -> CompletableFuture.completedFuture(List.of()),
                    new ConnectionListener() { }, Runnable::run, io);
            try {
                Thread.currentThread().interrupt();
                assertThrows(ZeroException.class, server::start);
                assertTrue(Thread.currentThread().isInterrupted());
            } finally { Thread.interrupted(); server.stop(); }
            assertTrue(!io.snapshot().closing());
        }
    }
    /** 全局与单连接预算分别生效；全局拒绝时回滚单连接预留。 */
    @Test void sharedBudgetRollsBackRejectedReservation() {
        var total = new java.util.concurrent.atomic.AtomicLong();
        KcpBudget first = new KcpBudget(total, 100, 80);
        KcpBudget second = new KcpBudget(total, 100, 80);
        assertTrue(first.reserve(70));
        assertTrue(!second.reserve(40));
        assertEquals(70, total.get());
        assertTrue(second.reserve(30));
        first.release(70);
        second.release(30);
        assertEquals(0, total.get());
    }
    /** 心跳不能掩盖发送窗口不前进；服务端必须按无进展期限回收。 */
    @Test void heartbeatsCannotKeepUnacknowledgedSendAlive() throws Exception {
        KcpOptions options = KcpTestConfig.create(1200, 10, 128, 8, 8, 512,
                Duration.ofSeconds(5), Duration.ofMillis(300), 1_000_000, true);
        try (var fixture = new KcpServerTest.Fixture(options);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), options)) {
            peer.raw(peer.heartbeat());
            KcpConnection connection = fixture.opened.get(2, TimeUnit.SECONDS);
            connection.sendFrame(KcpServerTest.frame(1)).toCompletableFuture().join();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
            while (!connection.isClosed() && System.nanoTime() < deadline) {
                peer.raw(peer.heartbeat());
                Thread.sleep(10);
            }
            assertTrue(connection.isClosed());
            assertEquals(0, fixture.server.pendingSendBytes());
        }
    }
    /** 待绑定票据也占内存；撤销释放预算后才能继续签发。 */
    @Test void lifecycleBudgetCapsPendingTicketsAndIsReclaimed() throws Exception {
        KcpOptions options = KcpTestConfig.create(1200, 10, 128, 8, 8, 512,
                Duration.ofSeconds(5), Duration.ofSeconds(1), 1200, true);
        try (var fixture = new KcpServerTest.Fixture(options)) {
            var control = new KcpTestControl();
            KcpTicket ticket = fixture.ticket(control);
            fixture.ticket(new KcpTestControl());
            var failure = assertThrows(java.util.concurrent.CompletionException.class,
                    () -> fixture.ticket(new KcpTestControl()));
            assertEquals(NetErrorCode.INBOUND_OVERFLOW, ((ZeroException) failure.getCause()).errorCode());
            fixture.server.fallbackToTcp(control, ticket.conv()).toCompletableFuture().join();
            fixture.ticket(new KcpTestControl());
            assertEquals(1024, fixture.server.pendingInboundBytes());
        }
    }
    /** 分片共享底层数组，部分 ACK 后不能把整帧内存预算提前归还。 */
    @Test void partialAcknowledgementsRetainSharedFrameBudget() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpServerTest.CONFIG);
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()),
                        fixture.server.boundPort(), KcpServerTest.CONFIG)) {
            peer.raw(peer.heartbeat());
            KcpConnection connection = fixture.opened.get(2, TimeUnit.SECONDS);
            peer.receiveLimit = 1;
            connection.sendFrame(new ProtocolFrame(1, 1, 0, null, new byte[3000])).toCompletableFuture().join();
            long reserved = fixture.server.pendingSendBytes();
            assertTrue(reserved > 0);
            peer.until(() -> peer.acceptedInbound == 1);
            peer.engine.flush(true, System.currentTimeMillis());
            peer.until(() -> peer.blockedInbound > 0);
            assertEquals(reserved, fixture.server.pendingSendBytes());
            connection.close().toCompletableFuture().join();
            assertEquals(0, fixture.server.pendingSendBytes());
        }
    }
    /** CallerRuns 一类执行器饱和策略也必须拒绝，已出队帧预算不能遗留。 */
    @Test void executorSwitchingToCallerRunsReleasesActiveFrame() throws Exception {
        try (var worker = Executors.newSingleThreadExecutor()) {
            AtomicInteger submissions = new AtomicInteger();
            AtomicInteger calls = new AtomicInteger();
            CompletableFuture<Void> opened = new CompletableFuture<>();
            var server = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), KcpServerTest.CONFIG,
                    new ZeroBinaryFrameCodec(), (connection, frame) -> {
                        calls.incrementAndGet(); return CompletableFuture.completedFuture(List.of());
                    }, new ConnectionListener() {
                        @Override public void onOpen(final IConnection connection) { opened.complete(null); }
                    }, task -> { if (submissions.getAndIncrement() == 0) worker.execute(task); else task.run(); }, null);
            try {
                server.start();
                KcpTicket ticket = server.issueTicket(new KcpTestControl()).toCompletableFuture().join();
                try (var peer = new KcpTestPeer(ticket, server.boundPort(), KcpServerTest.CONFIG)) {
                    peer.raw(peer.heartbeat());
                    opened.get(2, TimeUnit.SECONDS);
                    // 确保 open 的 completed 已离开业务执行器，再从 IO 发起下一次投递。
                    worker.submit(() -> { }).get(2, TimeUnit.SECONDS);
                    peer.send(KcpServerTest.frame(1));
                    peer.until(() -> server.failureCount() > 0 && server.pendingInboundBytes() == 0);
                    assertEquals(0, calls.get());
                }
            } finally { server.stop(); }
        }
    }
}
