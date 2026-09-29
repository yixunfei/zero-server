package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.buffer.UnpooledByteBufAllocator;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Stream;
import kcp.Kcp;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 固定种子和虚拟时钟，验证全部预设在双向弱网下的可靠有序交付。 @author zn */
class KcpFaultMatrixTest {
    static Stream<Arguments> scenarios() {
        return java.util.Arrays.stream(KcpProfile.values()).flatMap(profile -> Stream.of(
                Arguments.of(profile, 0, 20, 0), Arguments.of(profile, 5, 80, 30),
                Arguments.of(profile, 10, 200, 100), Arguments.of(profile, 20, 200, 100)));
    }
    /** 双向丢包/突发黑洞/乱序/重复，跨多个帧长保持完整且不重复。 */
    @ParameterizedTest @MethodSource("scenarios")
    void deliversUnderFaults(final KcpProfile profile, final int loss, final int rtt, final int jitter) {
        KcpOptions config = profile.options();
        try (var network = new Network(config, loss, rtt, jitter)) {
            List<byte[]> expected = new ArrayList<>();
            for (int id = 0; id < 12; id++) {
                int length = Math.min(config.maxMessageBytes(), new int[]{64, 512, 4096, 32000}[id % 4]);
                byte[] data = new byte[length]; new Random(id).nextBytes(data); expected.add(data);
                network.left.send(data); network.right.send(data);
            }
            for (network.now = 1000; network.now < 61000; network.now += 10) {
                network.left.engine.update(network.now); network.right.engine.update(network.now);
                while (!network.pending.isEmpty() && network.pending.peek().due() <= network.now) {
                    Flight flight = network.pending.remove(); flight.target().input(flight.bytes());
                }
                if (network.left.received.size() == 12 && network.right.received.size() == 12
                        && network.left.engine.waitSnd() == 0 && network.right.engine.waitSnd() == 0) break;
            }
            assertEquals(12, network.left.received.size()); assertEquals(12, network.right.received.size());
            for (int i = 0; i < 12; i++) {
                assertArrayEquals(expected.get(i), network.left.received.get(i));
                assertArrayEquals(expected.get(i), network.right.received.get(i));
            }
            assertEquals(0, network.left.engine.waitSnd()); assertEquals(0, network.right.engine.waitSnd());
            if (loss > 0) assertTrue(network.dropped > 0);
            System.out.printf(java.util.Locale.ROOT, "KCP_MATRIX profile=%s loss=%d rtt=%d jitter=%d virtual_ms=%d dropped=%d%n",
                    profile, loss, rtt, jitter, network.now - 1000, network.dropped);
        }
    }
    /** 有界故障网络，无后台线程或墙钟依赖。 @author zn */
    private static final class Network implements AutoCloseable {
        /** 参数。 */
        private final KcpOptions config;
        /** 丢包百分比。 */
        private final int loss;
        /** 往返延迟。 */
        private final int rtt;
        /** 抖动。 */
        private final int jitter;
        /** 固定随机序列。 */
        private final Random random = new Random(928);
        /** 有界排队网络包。 */
        private final PriorityQueue<Flight> pending = new PriorityQueue<>(Comparator.comparingLong(Flight::due));
        /** 模拟时刻。 */
        private long now = 1000;
        /** 丢弃数。 */
        private int dropped;
        /** 客户端。 */
        private final Endpoint left;
        /** 服务端。 */
        private final Endpoint right;
        Network(final KcpOptions config, final int loss, final int rtt, final int jitter) {
            this.config = config; this.loss = loss; this.rtt = rtt; this.jitter = jitter;
            left = new Endpoint(false); right = new Endpoint(true);
        }
        private void transmit(final Endpoint from, final ByteBuf body) {
            ByteBuf encoded = null;
            try {
                encoded = from.wire.encode(UnpooledByteBufAllocator.DEFAULT, body);
                if (random.nextInt(100) < loss || loss > 0 && now >= 1100 && now < 1300) { dropped++; return; }
                byte[] bytes = ByteBufUtil.getBytes(encoded);
                Endpoint target = from == left ? right : left;
                long due = now + rtt / 2L + (jitter == 0 ? 0 : random.nextInt(jitter + 1));
                assertTrue(pending.size() < 10000, "fault queue must stay bounded");
                pending.add(new Flight(due, bytes, target));
                if (loss > 0 && random.nextInt(10) == 0) pending.add(new Flight(due + 1, bytes, target));
            } finally { body.release(); if (encoded != null) encoded.release(); }
        }
        @Override public void close() { left.engine.release(); right.engine.release(); pending.clear(); }
        /** 独立算法和认证端点。 @author zn */
        private final class Endpoint {
            /** 收到的完整消息。 */
            private final List<byte[]> received = new ArrayList<>();
            /** 算法。 */
            private final Kcp engine;
            /** 认证封装。 */
            private final KcpDatagramCodec wire;
            Endpoint(final boolean server) {
                wire = new KcpDatagramCodec(new KcpTicket(7, new byte[32], Instant.parse("2026-09-29T00:00:00Z")), server);
                engine = new Kcp(7, (data, ignored) -> transmit(this, data)); config.tuning().apply(engine);
            }
            void send(final byte[] bytes) {
                ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
                try { assertEquals(0, engine.send(buffer)); } finally { buffer.release(); }
            }
            void input(final byte[] bytes) {
                ByteBuf packet = Unpooled.wrappedBuffer(bytes);
                ByteBuf body = wire.decode(packet);
                try {
                    if (body == null) return;
                    assertTrue(KcpSegments.valid(body, 7, config));
                    assertEquals(0, engine.input(body, true, now)); engine.flush(true, now);
                    while (engine.canRecv()) {
                        ByteBuf message = engine.mergeRecv();
                        try { received.add(ByteBufUtil.getBytes(message)); } finally { message.release(); }
                    }
                } finally { if (body != null) body.release(); packet.release(); }
            }
        }
    }
    /** 不可变虚拟数据报；重复包共用只读字节。 @author zn */
    private record Flight(long due, byte[] bytes, Network.Endpoint target) { }
}
