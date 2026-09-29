package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.netty.NettyIoResources;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 空闲授权回收与分档在途负载；检查资源上界，不将回环吞吐作为公网容量。 @author zn */
@Timeout(30)
class KcpCapacityTest {
    /** 超过单次调度批量的失效授权也及时回收，未绑定连接不轮询算法。 */
    @Test void reclaimsRevokedIdleBatchAndEnforcesCapacity() throws Exception {
        var config = KcpOptions.defaults().toBuilder().limits(new KcpLimits(128, 32, 512, 32768, 64L << 20)).build();
        try (var fixture = new KcpServerTest.Fixture(config)) {
            var controls = new ArrayList<KcpTestControl>();
            for (int i = 0; i < 128; i++) {
                var control = new KcpTestControl();
                controls.add(control);
                fixture.ticket(control);
            }
            assertEquals(128, fixture.server.snapshot().sessions());
            assertThrows(java.util.concurrent.CompletionException.class, () -> fixture.ticket(new KcpTestControl()));
            controls.forEach(control -> control.close().toCompletableFuture().join());
            await(() -> fixture.server.snapshot().sessions() == 0);
            assertEquals(0, fixture.server.snapshot().updates());
            assertEquals(0, fixture.server.pendingSendBytes());
            fixture.ticket(new KcpTestControl());
            assertEquals(1, fixture.server.snapshot().sessions());
        }
    }
    /** 32 个空闲、8 个活跃客户端共用受管 IO，逐档增加每客户端在途数量。 */
    @Test void servesIncreasingInflightLoadAlongsideIdleClients() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpOptions.defaults());
                var io = NettyIoResources.open(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1))) {
            var replies = new ConcurrentHashMap<Integer, CompletableFuture<ProtocolFrame>>();
            var clients = new ArrayList<KcpClient>();
            var controls = new ArrayList<KcpTestControl>();
            try {
                for (int i = 0; i < 40; i++) {
                    var control = new KcpTestControl();
                    controls.add(control);
                    var info = fixture.server.issueConnectInfo(control, "127.0.0.1", fixture.server.boundPort()).toCompletableFuture().join();
                    var client = new KcpClient(info, fixture.workers, (connection, frame) -> {
                        replies.get(frame.protocolId()).complete(frame);
                        return CompletableFuture.completedFuture(List.of());
                    }, new ConnectionListener() { }, io);
                    clients.add(client);
                    client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                }
                assertEquals(40, fixture.server.snapshot().connected());
                assertEquals(0, fixture.server.snapshot().updates());
                int completed = 0;
                for (int inflight : new int[]{1, 4, 16}) {
                    completed = runLoad(clients.subList(0, 8), replies, fixture, inflight, completed);
                }
                assertEquals(completed, fixture.calls.get());
                assertEquals(0, fixture.server.failureCount());
                await(() -> fixture.server.pendingSendBytes() == 0
                        && clients.stream().allMatch(client -> client.pendingSendBytes() == 0));
            } finally {
                for (var client : clients) client.close().toCompletableFuture().join();
                controls.forEach(control -> control.close().toCompletableFuture().join());
            }
            // 会话移出 IO 表之后，业务执行器仍可能持有 onClose 的生命周期预算。
            await(() -> fixture.server.snapshot().sessions() == 0 && fixture.server.pendingInboundBytes() == 0);
            assertEquals(0, fixture.server.pendingInboundBytes());
            assertTrue(!io.snapshot().closing());
        }
    }
    private static int runLoad(final List<KcpClient> clients,
            final Map<Integer, CompletableFuture<ProtocolFrame>> replies, final KcpServerTest.Fixture fixture,
            final int inflight, final int initial) throws Exception {
        int sequence = initial;
        long peak = 0;
        long started = System.nanoTime();
        for (int batch = 0; batch < 4; batch++) {
            var pending = new ArrayList<CompletableFuture<ProtocolFrame>>();
            for (var client : clients) {
                for (int frame = 0; frame < inflight; frame++) {
                    int id = ++sequence;
                    var received = new CompletableFuture<ProtocolFrame>();
                    replies.put(id, received);
                    pending.add(received);
                    client.sendFrame(new ProtocolFrame(id, 1, 0, null, new byte[4096])).toCompletableFuture().join();
                    peak = Math.max(peak, fixture.server.pendingSendBytes());
                }
            }
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).get(5, TimeUnit.SECONDS);
            for (var received : pending) {
                var frame = received.join();
                assertEquals(4096, frame.payload().length);
                replies.remove(frame.protocolId());
            }
        }
        double seconds = (System.nanoTime() - started) / 1e9;
        System.out.printf(java.util.Locale.ROOT,
                "KCP_CAPACITY idle=32 active=8 inflight_per_client=%d frames=%d fps=%.2f peak_send=%d updates=%d flushes=%d%n",
                inflight, sequence - initial, (sequence - initial) / seconds, peak,
                fixture.server.snapshot().updates(), fixture.server.snapshot().flushes());
        return sequence;
    }
    private static void await(final BooleanSupplier complete) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!complete.getAsBoolean() && System.nanoTime() < deadline) {
            new CompletableFuture<Void>().completeOnTimeout(null, 10, TimeUnit.MILLISECONDS).get(1, TimeUnit.SECONDS);
        }
        assertTrue(complete.getAsBoolean());
    }
}
