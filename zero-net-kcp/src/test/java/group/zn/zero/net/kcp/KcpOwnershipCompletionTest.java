package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

/** ownership SPI 的同步 Error 不能悬挂控制面结果或阻断会话预算回收。 @author zn */
@Timeout(10)
class KcpOwnershipCompletionTest {
    /** acquire 抛 Error 后请求明确失败，移除已安装会话，下一次申请仍有容量。 */
    @Test void acquisitionErrorCompletesTicketAndReclaimsSession() throws Exception {
        var fail = new AtomicBoolean(true);
        var failure = new AssertionError("acquire failed");
        var actual = new InMemoryKcpSessionStore();
        var store = failing(actual, "acquire", fail, failure);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var server = server(store, executor);
            try {
                server.start();
                var result = server.issueTicket(new KcpTestControl()).toCompletableFuture();
                var observed = assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS));
                var error = (ZeroException) observed.getCause();
                assertEquals(NetErrorCode.AUTHORIZATION_DENIED, error.errorCode());
                Throwable cause = error.getCause();
                while (cause instanceof java.util.concurrent.CompletionException) cause = cause.getCause();
                assertSame(failure, cause);
                assertEquals(0, server.snapshot().sessions());
                assertEquals(0, server.pendingInboundBytes());
                fail.set(false);
                assertNotNull(server.issueTicket(new KcpTestControl()).toCompletableFuture().get(2, TimeUnit.SECONDS));
            } finally { server.stop(); }
        }
    }

    /** release 抛 Error 仍须继续本地撤销；失联远程 owner 由原租约到期淘汰。 */
    @Test void releaseErrorDoesNotInterruptLocalSessionCleanup() throws Exception {
        var actual = new InMemoryKcpSessionStore();
        var store = failing(actual, "release", new AtomicBoolean(true), new AssertionError("release failed"));
        try (var executor = Executors.newSingleThreadExecutor()) {
            var server = server(store, executor);
            try {
                server.start();
                var control = new KcpTestControl();
                var ticket = server.issueTicket(control).toCompletableFuture().get(2, TimeUnit.SECONDS);
                server.fallbackToTcp(control, ticket.conv()).toCompletableFuture().get(2, TimeUnit.SECONDS);
                assertEquals(0, server.snapshot().sessions());
                assertEquals(0, server.pendingInboundBytes());
                assertEquals(1, server.failureCount());
            } finally { server.stop(); }
        }
    }

    private static KcpSessionStore failing(final KcpSessionStore actual, final String operation,
            final AtomicBoolean fail, final AssertionError failure) {
        return (KcpSessionStore) Proxy.newProxyInstance(KcpSessionStore.class.getClassLoader(),
                new Class<?>[]{KcpSessionStore.class}, (proxy, method, arguments) -> {
                    if (method.getName().equals(operation) && fail.get()) throw failure;
                    return method.invoke(actual, arguments);
                });
    }

    private static KcpServer server(final KcpSessionStore store, final java.util.concurrent.Executor executor) {
        var defaults = KcpOptions.defaults();
        var limits = defaults.limits();
        var config = defaults.toBuilder().limits(new KcpLimits(1, limits.maxQueuedFrames(),
                limits.maxPendingSegments(), limits.maxFrameBytes(), limits.maxInboundBytesTotal())).build();
        return new KcpServer(ServerOptions.kcp("127.0.0.1", 0).withIoThreads(1, 1), config,
                new ZeroBinaryFrameCodec(), (connection, frame) -> CompletableFuture.completedFuture(List.of()),
                new ConnectionListener() { }, executor, null, KcpAlgorithms.defaults(),
                new KcpSessionServices("ownership-test", store, Duration.ofSeconds(30), Duration.ofSeconds(3)));
    }
}
