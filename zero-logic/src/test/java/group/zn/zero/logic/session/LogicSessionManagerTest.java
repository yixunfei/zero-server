package group.zn.zero.logic.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import java.net.SocketAddress;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

/**
 * 业务逻辑会话管理器测试。
 *
 * @author zn
 */
class LogicSessionManagerTest {

    /**
     * 验证业务会话按连接打开、标记请求并关闭。
     */
    @Test
    void sessionShouldOpenMarkAndCloseByConnection() {
        LogicSessionManager manager = new LogicSessionManager();
        IConnection connection = new FakeConnection("conn-1");

        LogicSession session = manager.open(connection);
        session.attributes().put(StandardLogicSessionAttributes.CHANNEL_CODE, "dev");
        manager.markRequest(session);

        assertEquals("conn-1", session.connectionId());
        assertEquals("dev", session.attributes().get(StandardLogicSessionAttributes.CHANNEL_CODE).orElseThrow());
        assertEquals(1L, session.attributes().get(StandardLogicSessionAttributes.REQUEST_COUNT).orElseThrow());
        assertTrue(manager.findByConnectionId("conn-1").isPresent());
        assertTrue(manager.close(connection).isPresent());
        assertTrue(manager.findByConnectionId("conn-1").isEmpty());
    }

    /**
     * 测试连接。
     *
     * @param connectionId 连接标识。
     * @author zn
     */
    private record FakeConnection(String connectionId) implements IConnection {

        /**
         * 连接属性。
         */
        private static final ConnectionAttributes ATTRIBUTES = new DefaultConnectionAttributes();

        @Override
        public ServerType serverType() {
            return ServerType.TCP;
        }

        @Override
        public SocketAddress remoteAddress() {
            return null;
        }

        @Override
        public SocketAddress localAddress() {
            return null;
        }

        @Override
        public ConnectionAttributes attributes() {
            return ATTRIBUTES;
        }

        @Override
        public CompletionStage<Void> send(final Object message) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletionStage<Void> close() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
