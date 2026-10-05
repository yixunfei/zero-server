package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.http.HttpResponse;
import group.zn.zero.security.SecurityMetadataVerifier;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/** 受控业务任务验证 HTTP 错误必须终结响应或关闭连接，不能悬挂请求。 @author zn */
class HttpAsyncFailureTest {
    /** 同步 handler Error 和异步失败都应产生 500，并传播原始失败原因。 */
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void handlerErrorsProduceInternalServerError(final boolean asynchronous) throws Exception {
        Queue<Runnable> tasks = new ArrayDeque<>();
        var failure = new AssertionError("handler failed");
        var result = new CompletableFuture<HttpResponse>();
        var observed = new CompletableFuture<Throwable>();
        var channel = new EmbeddedChannel(new NettyHttpServer.NettyHttpChannelHandler(request -> {
            if (!asynchronous) throw failure;
            return result;
        }, tasks::add, SecurityMetadataVerifier.failClosed()), observer(observed));
        try {
            channel.writeInbound(request());
            tasks.remove().run();
            if (asynchronous) result.completeExceptionally(failure);
            channel.runPendingTasks();
            FullHttpResponse response = channel.readOutbound();
            assertNotNull(response, "handler failure must complete an HTTP response");
            try { assertEquals(500, response.status().code()); }
            finally { response.release(); }
            Throwable error = observed.get(2, TimeUnit.SECONDS);
            assertSame(failure, error instanceof ZeroException wrapped ? wrapped.getCause() : error);
        } finally { channel.finishAndReleaseAll(); }
    }

    /** 写失败不能留在无人读取的 Netty promise；立即关闭并传播 SEND_FAILED。 */
    @Test void asynchronousWriteFailureClosesConnection() throws Exception {
        var observed = new CompletableFuture<Throwable>();
        var write = new CompletableFuture<ChannelPromise>();
        var channel = new EmbeddedChannel(new ChannelOutboundHandlerAdapter() {
            @Override public void write(final ChannelHandlerContext context, final Object message,
                    final ChannelPromise promise) {
                ReferenceCountUtil.release(message);
                write.complete(promise);
            }
        }, new NettyHttpServer.NettyHttpChannelHandler(
                request -> CompletableFuture.completedFuture(HttpResponse.text(200, "ok")),
                Runnable::run, SecurityMetadataVerifier.failClosed()), observer(observed));
        try {
            channel.writeInbound(request());
            channel.runPendingTasks();
            var failure = new IllegalStateException("write failed");
            write.get(2, TimeUnit.SECONDS).setFailure(failure);
            channel.runPendingTasks();
            assertFalse(channel.isActive());
            assertTrue(observed.isDone());
            var error = (ZeroException) observed.get(2, TimeUnit.SECONDS);
            assertEquals(NetErrorCode.SEND_FAILED, error.errorCode());
            assertSame(failure, error.getCause());
        } finally { channel.finishAndReleaseAll(); }
    }

    private static DefaultFullHttpRequest request() {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, "/");
    }

    private static ChannelInboundHandlerAdapter observer(final CompletableFuture<Throwable> observed) {
        return new ChannelInboundHandlerAdapter() {
            @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable failure) {
                observed.complete(failure);
            }
        };
    }
}
