package group.zn.zero.examples.kcp;

import group.zn.zero.net.IConnection;
import group.zn.zero.net.kcp.KcpHandoff;
import group.zn.zero.net.kcp.KcpServer;
import group.zn.zero.protocol.ProtocolFrame;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletionStage;

/** 示例自己的 TLS 迁移控制协议；不是框架业务保留 ID，不接受 UDP 迁移指令。 @author zn */
final class KcpDemoMigration {
    /** 源节点冻结/移交请求与响应。 */
    static final int MIGRATE = 102;
    /** 目标节点认证后接管请求。 */
    static final int CLAIM = 103;
    private KcpDemoMigration() { }
    static CompletionStage<ProtocolFrame> handle(final KcpServer server, final IConnection control, final ProtocolFrame frame) {
        byte[] bytes = frame.payload();
        if (frame.protocolId() == MIGRATE) {
            if (bytes.length < 5 || bytes.length > 68) throw new IllegalArgumentException("invalid demo migration");
            int conv = ByteBuffer.wrap(bytes).getInt();
            String target = new String(bytes, 4, bytes.length - 4, StandardCharsets.US_ASCII);
            return server.migrate(control, conv, target, Duration.ofSeconds(3))
                    .thenApply(handoff -> KcpDemoWire.frame(MIGRATE, encode(handoff)));
        }
        return server.claimConnectInfo(control, decode(bytes), "127.0.0.1", server.boundPort())
                .thenApply(info -> KcpDemoWire.frame(KcpDemoWire.TICKET, info.encode()));
    }
    static byte[] request(final int conv, final String target) {
        byte[] node = target.getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(4 + node.length).putInt(conv).put(node).array();
    }
    private static byte[] encode(final KcpHandoff value) {
        byte[] node = value.targetNode().getBytes(StandardCharsets.US_ASCII);
        return ByteBuffer.allocate(20 + node.length).putInt(value.conv()).putLong(value.generation())
                .putLong(value.expiresAt().toEpochMilli()).put(node).array();
    }
    private static KcpHandoff decode(final byte[] bytes) {
        if (bytes.length < 21 || bytes.length > 84) throw new IllegalArgumentException("invalid demo handoff");
        ByteBuffer data = ByteBuffer.wrap(bytes);
        int conv = data.getInt(); long generation = data.getLong(); Instant expiry = Instant.ofEpochMilli(data.getLong());
        return new KcpHandoff(conv, generation, new String(bytes, 20, bytes.length - 20, StandardCharsets.US_ASCII), expiry);
    }
}
