package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import org.junit.jupiter.api.Test;

/** 可控执行器验证 KCP 回调不持调度锁、失败后有序排空并回收预算。 @author zn */
class KcpDispatchConcurrencyTest {
    @Test void pendingResponseKeepsBusyAndFirstAsyncFailureStopsBatch() {
        var endpoint = new Endpoint();
        endpoint.sendResult = new CompletableFuture<>();
        endpoint.dispatch.open();
        endpoint.runAll();
        endpoint.dispatch.receive(frame());
        endpoint.dispatch.receive(frame());
        endpoint.runAll();
        endpoint.result.complete(List.of(frame(), frame(), frame()));
        assertEquals(1, endpoint.responses);
        assertFalse(endpoint.dispatch.idle());
        assertTrue(endpoint.reserved > 512);
        var failure = new IllegalStateException("async send failed");
        endpoint.sendResult.completeExceptionally(failure);
        endpoint.runAll();
        assertSame(failure, endpoint.failure);
        assertEquals(1, endpoint.responses);
        assertEquals(1, endpoint.closedCallbacks);
        assertEquals(0, endpoint.reserved);
    }

    @Test void closeDuringResponseWaitsForPhysicalCompletionAndSkipsRemaining() {
        var endpoint = new Endpoint();
        endpoint.sendResult = new CompletableFuture<>();
        endpoint.dispatch.open();
        endpoint.runAll();
        endpoint.dispatch.receive(frame());
        endpoint.runAll();
        endpoint.result.complete(List.of(frame(), frame()));
        endpoint.dispatch.close();
        endpoint.runAll();
        assertEquals(0, endpoint.closedCallbacks);
        endpoint.sendResult.complete(null);
        endpoint.runAll();
        assertEquals(1, endpoint.responses);
        assertEquals(1, endpoint.closedCallbacks);
        assertEquals(0, endpoint.reserved);
    }
    /** 响应与执行器可能取得客户端生命周期锁，调度锁不得跨越这些调用。 */
    @Test void invokesExecutorAndResponsesOutsideDispatchMonitor() {
        Endpoint endpoint = new Endpoint();
        endpoint.dispatch.open();
        endpoint.runAll();
        endpoint.dispatch.receive(frame());
        endpoint.runAll();
        endpoint.result.complete(List.of(frame()));
        endpoint.runAll();
        assertEquals(1, endpoint.responses);
        assertFalse(endpoint.executorHeldMonitor);
        assertFalse(endpoint.responseHeldMonitor);
        endpoint.dispatch.close();
        endpoint.runAll();
        assertEquals(0, endpoint.reserved);
    }

    /** handler 的 Error 必须释放当前和排队帧，并且只发送一次关闭通知。 */
    @Test void handlerErrorReleasesAllInboundReservations() {
        Endpoint endpoint = new Endpoint();
        var failure = new AssertionError("handler failed");
        endpoint.handler = (connection, frame) -> { throw failure; };
        endpoint.dispatch.open();
        endpoint.runAll();
        endpoint.dispatch.receive(frame());
        endpoint.dispatch.receive(frame());
        endpoint.runAll();
        assertSame(failure, endpoint.failure);
        assertEquals(1, endpoint.closedCallbacks);
        assertEquals(0, endpoint.reserved);
        assertTrue(endpoint.dispatch.idle());
    }

    /** 异步完成后的发送同步抛错不能被遗弃的 whenComplete 阶段吞掉。 */
    @Test void asynchronousResponseSubmissionFailureClosesAndReclaimsQueue() {
        Endpoint endpoint = new Endpoint();
        var failure = new IllegalStateException("send rejected");
        endpoint.sendFailure = failure;
        endpoint.dispatch.open();
        endpoint.runAll();
        endpoint.dispatch.receive(frame());
        endpoint.dispatch.receive(frame());
        endpoint.runAll();
        endpoint.result.complete(List.of(frame()));
        endpoint.runAll();
        assertSame(failure, endpoint.failure);
        assertEquals(1, endpoint.closedCallbacks);
        assertEquals(0, endpoint.reserved);
        assertTrue(endpoint.dispatch.idle());
    }

    private static ProtocolFrame frame() { return new ProtocolFrame(1, 1, 0, null, new byte[32]); }

    /** 不创建线程的受控端点；记录跨模块回调锁状态和实际内存许可。 @author zn */
    private static final class Endpoint implements KcpDispatchEndpoint {
        /** 显式推进的业务执行队列。 */
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        /** 业务事务完成信号。 */
        private final CompletableFuture<List<ProtocolFrame>> result = new CompletableFuture<>();
        /** 实际调度器。 */
        private final KcpDispatch dispatch = new KcpDispatch(this);
        /** 可注入同步失败的业务入口。 */
        private ServerFrameHandler handler = (connection, frame) -> result;
        /** 内存预算。 */
        private long reserved;
        /** 发送数量。 */
        private int responses;
        /** 关闭通知数量。 */
        private int closedCallbacks;
        /** 执行器边界持锁记录。 */
        private boolean executorHeldMonitor;
        /** 发送边界持锁记录。 */
        private boolean responseHeldMonitor;
        /** 最终错误。 */
        private Throwable failure;
        /** 可注入的发送同步失败。 */
        private RuntimeException sendFailure;
        /** 可控的本地响应接收阶段。 */
        private CompletableFuture<Void> sendResult;

        private void runAll() { while (!tasks.isEmpty()) tasks.remove().run(); }
        @Override public CompletionStage<Void> respond(final ProtocolFrame frame) {
            responseHeldMonitor |= Thread.holdsLock(dispatch);
            responses++;
            if (sendFailure != null) throw sendFailure;
            return sendResult == null ? CompletableFuture.completedFuture(null) : sendResult;
        }
        @Override public boolean reserveInbound(final long bytes) { reserved += bytes; return true; }
        @Override public void releaseInbound(final long bytes) { reserved -= bytes; }
        @Override public Executor executor() {
            return task -> { executorHeldMonitor |= Thread.holdsLock(dispatch); tasks.add(task); };
        }
        @Override public boolean inIoThread() { return false; }
        @Override public IConnection connection() { return new KcpTestControl(); }
        @Override public KcpOptions options() { return KcpOptions.defaults(); }
        @Override public boolean authorized() { return true; }
        @Override public boolean closed() { return failure != null; }
        @Override public ConnectionListener listener() {
            return new ConnectionListener() {
                @Override public void onClose(final IConnection connection) { closedCallbacks++; }
            };
        }
        @Override public ServerFrameHandler handler() { return handler; }
        @Override public void closeWithFailure(final NetErrorCode code, final Throwable cause) {
            failure = cause;
            dispatch.close();
        }
    }
}
