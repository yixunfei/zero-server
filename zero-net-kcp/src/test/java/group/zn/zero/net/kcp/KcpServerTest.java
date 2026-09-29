package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.net.DatagramSocket;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 真实 socket 与有界资源回归，不依赖公网或外部数据库。 @author zn */
@Timeout(20)
class KcpServerTest {
    /** 测试预算。 */
    static final KcpOptions CONFIG = KcpTestConfig.create(1200, 10, 128, 8, 8, 512,
            Duration.ofSeconds(30), Duration.ofSeconds(5), 64L * 1024 * 1024, true);
    /** 分片、双向可靠传输、丢包/重排/重复，以及 ACK 后预算释放。 */
    @Test void exchangesFragmentedFramesUnderPacketFaultsInOrder() throws Exception {
        try (Fixture fixture = new Fixture(CONFIG)) {
            KcpTicket ticket = fixture.ticket(new KcpTestControl());
            try (var peer = new KcpTestPeer(ticket, fixture.server.boundPort(), CONFIG)) {
                peer.lossy = true;
                byte[] payload = new byte[20_000];
                new java.util.Random(17).nextBytes(payload);
                for (int i = 1; i <= 3; i++) peer.send(new ProtocolFrame(i, 1, 0, null, payload));
                peer.until(() -> peer.received.size() == 3);
                for (int i = 0; i < 3; i++) {
                    assertEquals(i + 1, peer.received.get(i).protocolId());
                    assertArrayEquals(payload, peer.received.get(i).payload());
                }
                peer.until(() -> fixture.server.pendingSendBytes() == 0);
                assertTrue(peer.dropped > 0);
                assertTrue(peer.inboundDropped > 0);
                assertTrue(peer.reordered > 0);
                assertEquals(3, fixture.calls.get());
                assertEquals(0, fixture.server.failureCount());
            }
        }
    }
    /** 未认证、明文控制通道与授权表满都必须显式拒绝。 */
    @Test void enforcesControlAuthenticationTlsAndCapacity() throws Exception {
        try (Fixture fixture = new Fixture(CONFIG)) {
            var control = new KcpTestControl();
            control.attributes().put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, false);
            assertError(NetErrorCode.TLS_REQUIRED, () -> fixture.ticket(control));
            control.attributes().put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, true);
            control.attributes().remove(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT);
            assertError(NetErrorCode.UNAUTHENTICATED, () -> fixture.ticket(control));
            for (int i = 0; i < CONFIG.maxSessions(); i++) fixture.ticket(new KcpTestControl());
            assertError(NetErrorCode.RATE_LIMITED, () -> fixture.ticket(new KcpTestControl()));
        }
    }
    /** 首个有效地址固定后，其他 UDP 端点即使有密钥也不可迁移；重放与坏包隔离。 */
    @Test void rejectsReplayTamperingOversizeAndAddressMigration() throws Exception {
        try (Fixture fixture = new Fixture(CONFIG)) {
            KcpTicket ticket = fixture.ticket(new KcpTestControl());
            try (var peer = new KcpTestPeer(ticket, fixture.server.boundPort(), CONFIG);
                    var attacker = new KcpTestPeer(ticket, fixture.server.boundPort(), CONFIG)) {
                byte[] hello = peer.heartbeat();
                peer.raw(hello);
                peer.until(() -> fixture.opened.isDone());
                peer.raw(hello);
                byte[] altered = peer.heartbeat();
                altered[altered.length - 1] ^= 1;
                peer.raw(altered);
                peer.raw(new byte[2000]);
                attacker.raw(attacker.heartbeat());
                peer.send(frame(1));
                peer.until(() -> peer.received.size() == 1 && fixture.server.rejectedDatagrams() >= 4);
                assertEquals(1, fixture.calls.get());
                assertEquals(0, fixture.server.failureCount());
            }
        }
    }
    /** 显式回退清理发送预算并使旧票据失效，TCP 控制连接保持可用。 */
    @Test void fallbackAndControlClosureRevokeTicketsWithoutReplay() throws Exception {
        try (Fixture fixture = new Fixture(CONFIG)) {
            var control = new KcpTestControl();
            KcpTicket ticket = fixture.ticket(control);
            try (var peer = new KcpTestPeer(ticket, fixture.server.boundPort(), CONFIG)) {
                peer.raw(peer.heartbeat());
                peer.until(() -> fixture.opened.isDone());
                KcpConnection connection = fixture.opened.join();
                connection.sendFrame(frame(1)).toCompletableFuture().join();
                assertError(NetErrorCode.AUTHORIZATION_DENIED, () -> fixture.server
                        .fallbackToTcp(new KcpTestControl(), ticket.conv()).toCompletableFuture().join());
                fixture.server.fallbackToTcp(control, ticket.conv()).toCompletableFuture().join();
                assertTrue(connection.isClosed());
                assertEquals(0, fixture.server.pendingSendBytes());
                peer.send(frame(2));
                peer.until(() -> fixture.server.rejectedDatagrams() > 0);
                assertEquals(0, fixture.calls.get());
                assertError(NetErrorCode.SEND_FAILED, () -> connection.sendFrame(frame(3)).toCompletableFuture().join());
                KcpTicket replacement = fixture.ticket(control);
                try (var next = new KcpTestPeer(replacement, fixture.server.boundPort(), CONFIG)) {
                    control.close();
                    next.send(frame(4));
                    next.until(() -> fixture.server.failureCount() > 0);
                    assertEquals(0, fixture.calls.get());
                }
            }
        }
    }
    /** 没有 ACK 时预留不能提前释放；达到预算后拒绝而非继续入队。 */
    @Test void retainsBackpressureUntilAcknowledgementOrClose() throws Exception {
        try (Fixture fixture = new Fixture(CONFIG)) {
            try (var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()), fixture.server.boundPort(), CONFIG)) {
                peer.raw(peer.heartbeat());
                peer.until(() -> fixture.opened.isDone());
                KcpConnection connection = fixture.opened.join();
                int accepted = 0;
                for (int i = 0; i < 1024; i++) {
                    try { connection.sendFrame(frame(1)).toCompletableFuture().join(); accepted++; }
                    catch (CompletionException overflow) {
                        assertEquals(NetErrorCode.OUTBOUND_OVERFLOW, ((ZeroException) overflow.getCause()).errorCode());
                        break;
                    }
                }
                assertTrue(accepted > 0 && accepted < 1024);
                assertTrue(fixture.server.pendingSendBytes() > 0);
                connection.close().toCompletableFuture().join();
                assertEquals(0, fixture.server.pendingSendBytes());
            }
        }
    }
    /** 借用资源不会被服务关闭；绑定失败和取消同样不得泄漏或终止借用组。 */
    @Test void closesOwnedSocketButPreservesBorrowedIoOnFailure() throws Exception {
        ServerOptions options = ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1);
        try (NettyIoResources io = NettyIoResources.open(options);
                ExecutorService workers = Executors.newSingleThreadExecutor();
                DatagramSocket occupied = new DatagramSocket(0)) {
            assertEquals(0, io.snapshot().bossThreads());
            var failed = server(ServerOptions.kcp("127.0.0.1", occupied.getLocalPort()), workers, io);
            assertThrows(ZeroException.class, failed::start);
            failed.stop();
            assertFalse(io.snapshot().closing());
            var working = server(options, workers, io);
            working.start();
            working.stop();
            working.stop();
            assertFalse(io.snapshot().closing());
            assertThrows(ZeroException.class, working::start);
        }
    }
    static ProtocolFrame frame(final int id) { return new ProtocolFrame(id, 1, 0, null, new byte[]{1, 2, 3}); }
    private static KcpServer server(final ServerOptions options, final ExecutorService workers, final NettyIoResources io) {
        return new KcpServer(options, CONFIG, new ZeroBinaryFrameCodec(),
                (connection, frame) -> CompletableFuture.completedFuture(List.of(frame)),
                new ConnectionListener() { }, workers, io);
    }
    private static void assertError(final NetErrorCode code, final Runnable operation) {
        CompletionException failure = assertThrows(CompletionException.class, operation::run);
        assertEquals(code, ((ZeroException) failure.getCause()).errorCode());
    }
    /** 每个测试独立资源，无共享端口或线程状态。 @author zn */
    static final class Fixture implements AutoCloseable {
        /** 测试拥有的业务线程池。 */
        final ExecutorService workers = Executors.newFixedThreadPool(2);
        /** 第一个已打开连接。 */
        final CompletableFuture<KcpConnection> opened = new CompletableFuture<>();
        /** 业务执行次数。 */
        final AtomicInteger calls = new AtomicInteger();
        /** 测试服务。 */
        final KcpServer server;
        Fixture(final KcpOptions config) {
            server = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1).withMaxFrameLength(32768),
                    config, new ZeroBinaryFrameCodec(), (connection, frame) -> {
                        calls.incrementAndGet();
                        return CompletableFuture.completedFuture(List.of(frame));
                    }, new ConnectionListener() {
                        @Override public void onOpen(final IConnection connection) { opened.complete((KcpConnection) connection); }
                    }, workers, null);
            server.start();
        }
        KcpTicket ticket(final IConnection control) { return server.issueTicket(control).toCompletableFuture().join(); }
        @Override public void close() { server.stop(); workers.close(); }
    }
}
