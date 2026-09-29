package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** 固定负载回环测量；首轮预热，后三轮记录，不用时延作不稳定断言。 @author zn */
@Timeout(90)
class KcpWorkloadTest {
    /** 验证不同帧长的连续可靠收发并输出可比较的原始数据。 */
    @Test void measuresSequentialEcho() throws Exception {
        try (var fixture = new KcpServerTest.Fixture(KcpOptions.defaults());
                var peer = new KcpTestPeer(fixture.ticket(new KcpTestControl()),
                        fixture.server.boundPort(), KcpOptions.defaults())) {
            int count = 0;
            for (int round = 0; round < 4; round++) {
                long[] latency = new long[64];
                long peak = 0;
                long started = System.nanoTime();
                for (int sample = 0; sample < latency.length; sample++) {
                    int size = new int[]{64, 512, 4096, 32000}[sample % 4];
                    long sent = System.nanoTime();
                    peer.send(new ProtocolFrame(++count, 1, 0, null, new byte[size]));
                    int expected = count;
                    peer.until(() -> peer.received.size() == expected);
                    latency[sample] = System.nanoTime() - sent;
                    peak = Math.max(peak, fixture.server.pendingSendBytes());
                    assertEquals(count, peer.received.get(count - 1).protocolId());
                }
                double seconds = (System.nanoTime() - started) / 1e9;
                Arrays.sort(latency);
                System.out.printf(java.util.Locale.ROOT,
                        "KCP_PROBE round=%d frames=64 fps=%.2f p50_ms=%.3f p95_ms=%.3f p99_ms=%.3f peak_send=%d%n",
                        round, 64 / seconds, latency[32] / 1e6, latency[60] / 1e6, latency[63] / 1e6, peak);
            }
        }
    }
}
