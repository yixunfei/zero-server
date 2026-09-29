package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.protocol.ProtocolFrame;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/** 生产客户端、场景描述和控制面恢复回归。 @author zn */
@Timeout(20)
class KcpClientTest {
    /** 独立 Python struct 生成的固定 ZKCI 向量，约束跨语言字节布局。 */
    @Test void matchesIndependentControlVector() throws Exception {
        byte[] key = new byte[32];
        for (int i = 0; i < key.length; i++) key[i] = (byte) i;
        var info = new KcpConnectInfo("127.0.0.1", 9001,
                new KcpTicket(0x11223344, key, java.time.Instant.EPOCH), KcpOptions.defaults());
        try (var stream = KcpClientTest.class.getResourceAsStream("/zkci-v1.hex")) {
            byte[] expected = java.util.HexFormat.of().parseHex(new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).trim());
            assertArrayEquals(expected, info.encode());
            assertEquals(info.options(), KcpConnectInfo.decode(expected).options());
        }
    }
    /** 五场景均可独立握手、心跳、发送和关闭，描述往返不丢参数。 */
    @ParameterizedTest @EnumSource(KcpProfile.class)
    void connectsEveryProfile(final KcpProfile profile) throws Exception {
        try (var fixture = new KcpServerTest.Fixture(profile.options())) {
            var info = fixture.server.issueConnectInfo(new KcpTestControl(), "127.0.0.1", fixture.server.boundPort())
                    .toCompletableFuture().get(2, TimeUnit.SECONDS);
            var decoded = KcpConnectInfo.decode(info.encode());
            assertEquals(info.options(), decoded.options());
            assertArrayEquals(info.ticket().key(), decoded.ticket().key());
            var received = new CompletableFuture<ProtocolFrame>();
            var client = new KcpClient(decoded, fixture.workers, (connection, frame) -> {
                received.complete(frame); return CompletableFuture.completedFuture(List.of());
            }, new ConnectionListener() { }, null);
            try {
                client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                client.sendFrame(KcpServerTest.frame(42)).toCompletableFuture().join();
                assertEquals(42, received.get(2, TimeUnit.SECONDS).protocolId());
                assertEquals(KcpClient.State.CONNECTED, client.state());
            } finally { client.close().toCompletableFuture().get(2, TimeUnit.SECONDS); }
            assertEquals(0, client.pendingSendBytes());
        }
    }
    /** 恢复重新领票，回退完成后旧 KCP 不能提交或重放。 */
    @Test void recoversAndFallsBackWithoutReplay() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpOptions.defaults())) {
            var control = new KcpTestControl();
            var plane = new KcpControlPlane() {
                @Override public java.util.concurrent.CompletionStage<KcpConnectInfo> acquire() {
                    return fixture.server.issueConnectInfo(control, "127.0.0.1", fixture.server.boundPort());
                }
                @Override public java.util.concurrent.CompletionStage<Void> revoke(final int conv) {
                    return fixture.server.fallbackToTcp(control, conv);
                }
            };
            var manager = new KcpRecovery(plane, fixture.workers,
                    (connection, frame) -> CompletableFuture.completedFuture(List.of()), new ConnectionListener() { }, null);
            try {
                var first = manager.reconnect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                var second = manager.reconnect().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertNotEquals(first.connectInfo().ticket().conv(), second.connectInfo().ticket().conv());
                assertEquals(KcpClient.State.CLOSED, first.state());
                manager.fallbackToTcp().toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(KcpRecovery.State.TCP, manager.state());
                assertThrows(java.util.concurrent.CompletionException.class,
                        () -> manager.send(KcpServerTest.frame(1)).toCompletableFuture().join());
                assertEquals(0, fixture.calls.get());
            } finally { manager.close().toCompletableFuture().join(); }
        }
    }
    /** UDP 黑洞按绑定时限失败，停止回收出站预算。 */
    @Test void timesOutAndClosesBlackholedHandshake() throws Exception {
        try (var workers = java.util.concurrent.Executors.newSingleThreadExecutor();
                var blackhole = new java.net.DatagramSocket(0)) {
            var config = KcpOptions.defaults().toBuilder().timeouts(new KcpTimeouts(Duration.ofMillis(100),
                    Duration.ofMillis(50), Duration.ofSeconds(1), Duration.ofSeconds(1), Duration.ofMinutes(1))).build();
            var info = new KcpConnectInfo("127.0.0.1", blackhole.getLocalPort(),
                    new KcpTicket(7, new byte[32], java.time.Instant.now().plusSeconds(30)), config);
            var client = new KcpClient(info, workers, (connection, frame) -> CompletableFuture.completedFuture(List.of()),
                    new ConnectionListener() { }, null);
            try {
                assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> client.connect().toCompletableFuture().get(2, TimeUnit.SECONDS));
            } finally { client.close().toCompletableFuture().join(); }
            assertEquals(0, client.pendingSendBytes());
        }
    }
    /** 非法配置和版本在资源创建前拒绝。 */
    @Test void rejectsInvalidDescriptionsAndBudgets() {
        var info = new KcpConnectInfo("localhost", 9001,
                new KcpTicket(7, new byte[32], java.time.Instant.now()), KcpOptions.defaults());
        byte[] encoded = info.encode(); encoded[7] = 2;
        assertThrows(IllegalArgumentException.class, () -> KcpConnectInfo.decode(encoded));
        assertThrows(IllegalArgumentException.class, () -> KcpConnectInfo.decode(new byte[4]));
        assertThrows(IllegalArgumentException.class, () -> new KcpConnectInfo("0.0.0.0", 0, info.ticket(), info.options()));
        assertThrows(IllegalArgumentException.class, () -> KcpOptions.builder(KcpProfile.LOW_LATENCY)
                .limits(new KcpLimits(10, 10, 512, 999999, 10000)).build());
        assertFalse(info.toString().contains("[0, 0, 0"));
    }
}
