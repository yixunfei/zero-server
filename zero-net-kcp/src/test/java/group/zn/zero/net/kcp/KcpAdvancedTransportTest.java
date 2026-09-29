package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** FEC 算法/预算和真实 UDP 加密、路径迁移的闭环验收。 @author zn */
@Timeout(20)
class KcpAdvancedTransportTest {
    /** XOR 恢复一个擦除，RS 恢复多个擦除，重复 shard 不重复交付。 */
    @Test void recoversFullAndPartialGroupsWithinBudgets() {
        for (KcpFecOptions options : List.of(KcpFecOptions.xor(4), KcpFecOptions.reedSolomon(4, 2))) {
            var sent = new ArrayList<byte[]>(); var recovered = new ArrayList<byte[]>();
            var budget = new AtomicLong(); var reasons = new ArrayList<String>();
            try (var encoder = fec(options, budget, reasons); var decoder = fec(options, budget, reasons)) {
                for (int i = 0; i < 4; i++) encoder.send(new byte[]{(byte) (i + 1), 42}, 0, sent::add);
                int lost = options.parityShards();
                for (int i = lost; i < sent.size(); i++) { decoder.input(sent.get(i), 0, recovered::add); decoder.input(sent.get(i), 0, recovered::add); }
                assertEquals(4, recovered.size());
                assertEquals(List.of(1, 2, 3, 4), recovered.stream().map(b -> (int) b[0]).sorted().toList());
                assertEquals(lost, reasons.stream().filter("fec.recovered"::equals).count());
                sent.clear(); recovered.clear(); encoder.send(new byte[]{7}, 0, sent::add);
                encoder.tick(options.flushDelay().toNanos(), sent::add);
                for (int i = 1; i < sent.size(); i++) decoder.input(sent.get(i), 0, recovered::add);
                assertEquals(1, recovered.size()); assertArrayEquals(new byte[]{7}, recovered.getFirst());
            }
            assertEquals(0, budget.get());
        }
    }
    /** 无法恢复的组按期限释放，非法 shard 不分配无界内存。 */
    @Test void expiresIncompleteFecGroupsAndRejectsMalformedPackets() {
        var options = KcpFecOptions.reedSolomon(4, 2); var budget = new AtomicLong();
        var reasons = new ArrayList<String>(); var packets = new ArrayList<byte[]>();
        try (var encoder = fec(options, budget, reasons); var decoder = fec(options, budget, reasons)) {
            encoder.send(new byte[]{3}, 0, packets::add);
            decoder.input(packets.getFirst(), 0, bytes -> { });
            assertTrue(budget.get() > 0);
            byte[] bad = packets.getFirst().clone(); bad[12] = 99;
            decoder.input(bad, 1, bytes -> fail("invalid shard delivered"));
            decoder.tick(options.expiry().toNanos(), bytes -> { });
            assertTrue(reasons.contains("fec.invalid")); assertTrue(reasons.contains("fec.expired"));
        }
        assertEquals(0, budget.get());
    }
    /** HMAC/ChaCha/AES 与两种 FEC 都在受管客户端完成握手、回显和预算释放。 */
    @Test void exchangesEncryptedFecTrafficOverRealSockets() throws Exception {
        for (int protection : new int[]{1, 2, 3}) for (var fec : List.of(KcpFecOptions.xor(4), KcpFecOptions.reedSolomon(4, 2))) {
            var options = KcpOptions.defaults().toBuilder().transport(KcpTransportOptions.of(protection, fec, KcpPathOptions.validated())).build();
            try (var fixture = new KcpServerTest.Fixture(options)) {
                var info = fixture.server.issueConnectInfo(new KcpTestControl(), "127.0.0.1", fixture.server.boundPort()).toCompletableFuture().join();
                var reply = new CompletableFuture<ProtocolFrame>();
                var client = new KcpClient(KcpConnectInfo.decode(info.encode()), fixture.workers, (connection, frame) -> {
                    reply.complete(frame); return CompletableFuture.completedFuture(List.of());
                }, new ConnectionListener() { }, null);
                try {
                    client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                    byte[] payload = new byte[8000]; Arrays.fill(payload, (byte) 37);
                    client.sendFrame(new ProtocolFrame(17, 1, 0, null, payload)).toCompletableFuture().join();
                    assertArrayEquals(payload, reply.get(5, TimeUnit.SECONDS).payload());
                    assertEquals(0, fixture.server.failureCount());
                } finally { client.close().toCompletableFuture().join(); }
            }
        }
    }
    /** 重绑保留会话和 KCP 队列，服务端只在完成挑战后改变地址。 */
    @Test void rebindsPortWithoutRecreatingSession() throws Exception {
        var options = KcpOptions.defaults().toBuilder().transport(KcpTransportOptions.of(2, KcpFecOptions.none(), KcpPathOptions.validated())).build();
        try (var fixture = new KcpServerTest.Fixture(options)) {
            var info = fixture.server.issueConnectInfo(new KcpTestControl(), "127.0.0.1", fixture.server.boundPort()).toCompletableFuture().join();
            var reply = new CompletableFuture<ProtocolFrame>();
            var client = new KcpClient(info, fixture.workers, (connection, frame) -> {
                reply.complete(frame); return CompletableFuture.completedFuture(List.of());
            }, new ConnectionListener() { }, null);
            try {
                client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var connection = fixture.opened.get(2, TimeUnit.SECONDS); var before = client.localAddress();
                client.rebind().toCompletableFuture().get(4, TimeUnit.SECONDS);
                assertNotEquals(before, client.localAddress()); assertEquals(((java.net.InetSocketAddress) client.localAddress()).getPort(), ((java.net.InetSocketAddress) connection.remoteAddress()).getPort());
                client.sendFrame(KcpServerTest.frame(71)).toCompletableFuture().join();
                assertEquals(71, reply.get(2, TimeUnit.SECONDS).protocolId());
                assertEquals(1, fixture.server.snapshot().connected()); assertEquals(1L, fixture.server.snapshot().reasons().get("path.migrated"));
            } finally { client.close().toCompletableFuture().join(); }
        }
    }
    private static KcpFecCodec fec(final KcpFecOptions options, final AtomicLong bytes, final List<String> reasons) {
        return new KcpFecCodec(options, KcpAlgorithms.defaults().fec(options), 1200,
                amount -> KcpBudget.reserve(bytes, 8L << 20, amount), amount -> bytes.addAndGet(-amount), reasons::add);
    }
}
