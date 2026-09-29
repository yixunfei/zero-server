package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.Unpooled;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 真实 UDP 双节点迁移：数据面屏障、代际隔离、续租故障与业务排空。 @author zn */
@Timeout(20)
class KcpClusterTest {
    /** 迁移保持 conv、轮换密钥，旧 owner/旧票据和重复 claim 均被拒绝。 */
    @Test void transfersEncryptedFecSessionAndRejectsOldPackets() throws Exception {
        var store = new InMemoryKcpSessionStore();
        try (var a = new Node("a", store); var b = new Node("b", store)) {
            var control = new KcpTestControl(); var info = a.info(control); var replies = new LinkedBlockingQueue<ProtocolFrame>();
            KcpClient first = a.client(info, replies);
            try {
                first.sendFrame(KcpServerTest.frame(1)).toCompletableFuture().join();
                assertEquals(1, replies.poll(3, TimeUnit.SECONDS).protocolId());
                assertThrows(CompletionException.class, () -> a.server.migrate(control, info.ticket().conv(), "b", Duration.ofSeconds(2)).toCompletableFuture().join());
                first.quiesce(Duration.ofSeconds(3)).toCompletableFuture().get(4, TimeUnit.SECONDS);
                assertThrows(CompletionException.class, () -> first.sendFrame(KcpServerTest.frame(2)).toCompletableFuture().join());
                var handoff = a.server.migrate(control, info.ticket().conv(), "b", Duration.ofSeconds(2)).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(KcpSessionOwner.Phase.PENDING, store.owner(handoff.conv()).toCompletableFuture().join().phase());
                assertNotNull(store.load(handoff.conv()).toCompletableFuture().join());
                var next = b.server.claimConnectInfo(new KcpTestControl(), handoff, "127.0.0.1", b.server.boundPort()).toCompletableFuture().get(3, TimeUnit.SECONDS);
                assertEquals(info.ticket().conv(), next.ticket().conv()); assertEquals(2, next.ticket().generation());
                assertFalse(java.util.Arrays.equals(info.ticket().key(), next.ticket().key()));
                assertThrows(CompletionException.class, () -> b.server.claimConnectInfo(new KcpTestControl(), handoff, "127.0.0.1", b.server.boundPort()).toCompletableFuture().join());
                assertFalse(store.renew(handoff.conv(), "a", 1, Duration.ofSeconds(1)).toCompletableFuture().join());
                KcpClient second = b.client(next, replies);
                try {
                    replay(info, b.server.boundPort());
                    second.sendFrame(KcpServerTest.frame(3)).toCompletableFuture().join();
                    assertEquals(3, replies.poll(3, TimeUnit.SECONDS).protocolId());
                    assertEquals(0, a.server.snapshot().sessions()); assertEquals(1, b.server.snapshot().sessions());
                    assertTrue(b.server.rejectedDatagrams() > 0); assertEquals(0, b.server.failureCount());
                } finally { second.close().toCompletableFuture().join(); }
            } finally { first.close().toCompletableFuture().join(); }
        }
    }
    /** 屏障等待已经执行的 handler 及响应 ACK，不把未完成业务误报为已迁移。 */
    @Test void quiescenceWaitsForAdmittedBusiness() throws Exception {
        try (var node = new Node("drain", new InMemoryKcpSessionStore())) {
            node.block = new CompletableFuture<>(); var replies = new LinkedBlockingQueue<ProtocolFrame>();
            var client = node.client(node.info(new KcpTestControl()), replies);
            try {
                client.sendFrame(KcpServerTest.frame(8)).toCompletableFuture().join();
                node.entered.get(2, TimeUnit.SECONDS);
                var drain = client.quiesce(Duration.ofSeconds(3)).toCompletableFuture();
                assertFalse(drain.isDone()); node.block.complete(List.of(KcpServerTest.frame(8)));
                drain.get(4, TimeUnit.SECONDS); assertEquals(8, replies.poll(1, TimeUnit.SECONDS).protocolId());
                assertEquals(0, node.server.pendingSendBytes());
            } finally { client.close().toCompletableFuture().join(); }
        }
    }
    /** 续租错误不降级为本地双主，连接在操作期限内关闭。 */
    @Test void failsClosedWhenRenewalStoreFails() throws Exception {
        var actual = new InMemoryKcpSessionStore();
        KcpSessionStore failed = (KcpSessionStore) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{KcpSessionStore.class}, (proxy, method, arguments) -> method.getName().equals("renew")
                        ? CompletableFuture.failedFuture(new IllegalStateException("simulated store outage")) : method.invoke(actual, arguments));
        try (var node = new Node("lost", failed)) {
            var client = node.client(node.info(new KcpTestControl()), new LinkedBlockingQueue<>());
            try {
                node.closed.get(3, TimeUnit.SECONDS);
                assertEquals(0, node.server.snapshot().sessions()); assertEquals(1L, node.server.snapshot().reasons().get("lease.lost"));
            } finally { client.close().toCompletableFuture().join(); }
        }
    }
    private static void replay(final KcpConnectInfo info, final int port) throws Exception {
        try (var codec = new KcpDatagramCodec(info.ticket(), false, info.options().transport()); var socket = new DatagramSocket()) {
            var packet = codec.encode(Unpooled.EMPTY_BUFFER.alloc(), Unpooled.EMPTY_BUFFER);
            try {
                byte[] bytes = new byte[packet.readableBytes()]; packet.getBytes(0, bytes);
                socket.send(new DatagramPacket(bytes, bytes.length, InetAddress.getLoopbackAddress(), port));
            } finally { packet.release(); }
        }
    }
    /** 每个节点拥有独立服务资源，存储可共享但不共享 KCP 算法或业务状态。 @author zn */
    private static final class Node implements AutoCloseable {
        /** 测试受管执行器。 */
        private final ExecutorService worker = Executors.newFixedThreadPool(2);
        /** 实际服务。 */
        private final KcpServer server;
        /** 可选异步业务屏障。 */
        private volatile CompletableFuture<List<ProtocolFrame>> block;
        /** 业务已开始信号。 */
        private final CompletableFuture<Void> entered = new CompletableFuture<>();
        /** 服务连接关闭信号。 */
        private final CompletableFuture<Void> closed = new CompletableFuture<>();
        Node(final String name, final KcpSessionStore store) {
            var options = KcpOptions.defaults().toBuilder().transport(KcpTransportOptions.of(2,
                    KcpFecOptions.reedSolomon(4, 2), KcpPathOptions.validated())).build();
            server = new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), options, new ZeroBinaryFrameCodec(),
                    (connection, frame) -> { entered.complete(null); return block == null ? CompletableFuture.completedFuture(List.of(frame)) : block; },
                    new ConnectionListener() { @Override public void onClose(final IConnection connection) { closed.complete(null); } },
                    worker, null, KcpAlgorithms.defaults(), new KcpSessionServices(name, store, Duration.ofMillis(900), Duration.ofMillis(200)));
            server.start();
        }
        KcpConnectInfo info(final IConnection control) { return server.issueConnectInfo(control, "127.0.0.1", server.boundPort()).toCompletableFuture().join(); }
        KcpClient client(final KcpConnectInfo info, final LinkedBlockingQueue<ProtocolFrame> received) throws Exception {
            var client = new KcpClient(info, worker, (connection, frame) -> {
                received.add(frame); return CompletableFuture.completedFuture(List.of());
            }, new ConnectionListener() { }, null);
            client.connect().toCompletableFuture().get(3, TimeUnit.SECONDS); return client;
        }
        @Override public void close() { server.stop(); worker.close(); }
    }
}
