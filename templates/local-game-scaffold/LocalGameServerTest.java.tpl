package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import __PACKAGE__.generated.dto.GameMoveProtocolDTO;
import __PACKAGE__.generated.dto.codec.GameMoveProtocolDTOCodec;
import __PACKAGE__.generated.protocol.ProtocolIds;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.buffer.ZeroWriter;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 本地 TCP 入口回归：真实回包、业务失败、绑定限制和资源释放。 @author zn */
class LocalGameServerTest {
    @Test
    void respondsAfterBusinessAndReleasesPort() throws Exception {
        int port;
        var server = __SERVER_CLASS__.start(__SERVER_CLASS__.overrides(Map.of(
                "zero.net.host", "127.0.0.1", "zero.net.port", "0")));
        try (server) {
            assertTrue(server.running());
            String address = server.bindAddress();
            port = Integer.parseInt(address.substring(address.lastIndexOf(':') + 1));
            assertEquals(1001L, __CLIENT_CLASS__.smoke(address, server.runtime()).uid());
            ProtocolFrame response = __CLIENT_CLASS__.exchange(address, new ZeroBinaryFrameCodec(), invalidMove());
            assertEquals(group.zn.zero.protocol.ProtocolFeature.ERROR.mask(), response.flags());
            assertTrue(new String(response.payload(), StandardCharsets.UTF_8).contains("reason=ZERO-"));
            assertEquals(2, server.handledRequests());
            assertEquals(1, server.dispatchFailures());
        }
        assertFalse(server.running());
        server.close();
        try (ServerSocket rebound = new ServerSocket()) {
            rebound.setReuseAddress(true);
            rebound.bind(new InetSocketAddress("127.0.0.1", port));
        }
    }

    @Test
    void rejectsPublicBindBeforeStartingResources() {
        assertThrows(IllegalArgumentException.class, () -> __SERVER_CLASS__.start(
                __SERVER_CLASS__.overrides(Map.of("zero.net.host", "0.0.0.0"))));
    }

    @Test
    void occupiedPortFailsAndAnotherServerCanStart() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0, 1, java.net.InetAddress.getLoopbackAddress())) {
            assertThrows(RuntimeException.class, () -> __SERVER_CLASS__.start(__SERVER_CLASS__.overrides(Map.of(
                    "zero.net.host", "127.0.0.1", "zero.net.port", Integer.toString(occupied.getLocalPort())))));
        }
        try (var server = __SERVER_CLASS__.start(__SERVER_CLASS__.overrides(Map.of("zero.net.port", "0")))) {
            assertTrue(server.running());
        }
    }

    private static ProtocolFrame invalidMove() {
        GameMoveProtocolDTO request = new GameMoveProtocolDTO();
        request.uid = 1001L;
        request.sceneId = "never-entered";
        request.traceId = "trace-tcp-failure";
        try (ZeroWriter writer = new ZeroWriter()) {
            GameMoveProtocolDTOCodec.INSTANCE.write(writer, request);
            return new ProtocolFrame(ProtocolIds.GAME_MOVE_PROTOCOL, 1, 0, null, writer.toByteArray());
        }
    }
}
