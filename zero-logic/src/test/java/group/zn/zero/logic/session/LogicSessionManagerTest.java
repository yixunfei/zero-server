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
    /** 统计键类型冲突不能先提交计数或时间。 */
    @Test void conflictingStatisticKeyDoesNotPartiallyCommitRequest() {
        var manager = new LogicSessionManager();
        var session = manager.open(new FakeConnection("conflict"));
        var attributes = session.attributes();
        attributes.put(LogicSessionAttributeKey.of(StandardLogicSessionAttributes.LAST_REQUEST_TIME_MILLIS.name(),
                String.class), "wrong-type");
        var before = attributes.snapshot();
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> manager.markRequest(session));
        assertEquals(before, attributes.snapshot());
    }
    @Test void capacityIsReleasedAndClosedReferencesRejectAllWrites() {
        var manager = new LogicSessionManager(1);
        var first = new FakeConnection("first");
        var second = new FakeConnection("second");
        var session = manager.open(first);
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> manager.open(second));
        org.junit.jupiter.api.Assertions.assertSame(session, manager.open(first));
        manager.close(first);
        assertTrue(session.isClosed());
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> session.attributes().update(StandardLogicSessionAttributes.REQUEST_COUNT, ignored -> null));
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> session.attributes().remove(StandardLogicSessionAttributes.REQUEST_COUNT));
        assertEquals(0L, session.attributes().get(StandardLogicSessionAttributes.REQUEST_COUNT).orElseThrow());
        assertEquals(second, manager.open(second).connection());
    }

    @Test void concurrentMarkRequestsPublishConsistentStatistics() throws Exception {
        var manager = new LogicSessionManager();
        var connection = new FakeConnection("concurrent");
        var session = manager.open(connection);
        try (var workers = java.util.concurrent.Executors.newFixedThreadPool(4)) {
            var start = new java.util.concurrent.CountDownLatch(1);
            var jobs = new java.util.ArrayList<CompletableFuture<Void>>();
            for (int worker = 0; worker < 4; worker++) jobs.add(CompletableFuture.runAsync(() -> {
                try { start.await(); }
                catch (InterruptedException failure) { Thread.currentThread().interrupt(); throw new AssertionError(failure); }
                for (int request = 0; request < 1000; request++) manager.markRequest(session);
            }, workers));
            start.countDown();
            CompletableFuture.allOf(jobs.toArray(CompletableFuture[]::new)).get(3, java.util.concurrent.TimeUnit.SECONDS);
        }
        var snapshot = session.attributes().snapshot();
        assertEquals(4000L, snapshot.get(StandardLogicSessionAttributes.REQUEST_COUNT.name()));
        assertTrue((Long) snapshot.get(StandardLogicSessionAttributes.LAST_REQUEST_INTERVAL_MILLIS.name()) >= 0);
        manager.close(connection);
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class, () -> manager.markRequest(session));
        assertEquals(snapshot, session.attributes().snapshot());
    }

    @Test void failedAndRecursiveUpdaterLeaveValueUnchanged() {
        var attributes = new DefaultLogicSessionAttributes();
        var key = LogicSessionAttributeKey.of("value", String.class);
        attributes.put(key, "before");
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> attributes.update(key, value -> { throw new IllegalStateException("failed"); }));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> attributes.update(key, value -> { attributes.put(key, "reenter"); return "after"; }));
        assertEquals("before", attributes.get(key).orElseThrow());
        org.junit.jupiter.api.Assertions.assertThrows(UnsupportedOperationException.class,
                () -> attributes.snapshot().clear());
    }
    @Test void differentConnectionObjectCannotTakeOverIdOrCloseNewSession() {
        var manager = new LogicSessionManager();
        var old = new FakeConnection("same");
        var next = new FakeConnection("same");
        var session = manager.open(old);
        org.junit.jupiter.api.Assertions.assertSame(session, manager.open(old));
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> manager.open(next));
        assertTrue(manager.close(next).isEmpty());
        manager.close(old);
        var reopened = manager.open(next);
        assertTrue(manager.close(old).isEmpty());
        org.junit.jupiter.api.Assertions.assertSame(reopened, manager.findByConnectionId("same").orElseThrow());
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> manager.markRequest(session));
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> session.attributes().put(StandardLogicSessionAttributes.CHANNEL_CODE, "late"));
    }

    @Test void attributeNamesHaveOneActiveTypeAndNullUpdateDeletes() {
        var attributes = new DefaultLogicSessionAttributes();
        var text = LogicSessionAttributeKey.of("same", String.class);
        var number = LogicSessionAttributeKey.of("same", Long.class);
        attributes.put(text, "value");
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> attributes.put(number, 1L));
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> attributes.get(number));
        org.junit.jupiter.api.Assertions.assertThrows(group.zn.zero.core.error.ZeroException.class,
                () -> attributes.remove(number));
        assertTrue(attributes.update(text, ignored -> null).isEmpty());
        attributes.put(number, 1L);
        assertEquals(java.util.Map.of("same", 1L), attributes.snapshot());
    }

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

    /** 网络关闭和异常回调都会回收精确连接会话。 */
    @Test void connectionLifecycleCallbacksReleaseSession() {
        var manager = new LogicSessionManager(1);
        var connection = new FakeConnection("lifecycle");
        var session = manager.open(connection);
        manager.onException(connection, new IllegalStateException("disconnected"));
        assertTrue(session.isClosed());
        assertTrue(manager.findByConnectionId("lifecycle").isEmpty());
        assertTrue(manager.open(new FakeConnection("next")).connectionId().equals("next"));
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
