package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.ReferenceCountUtil;
import java.util.ArrayList;
import java.util.List;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** HTTP 顺序、绝对期限和队列引用释放回归。 @author zn */
class NettyHttpRequestGuardTest {
    @Test void configuredResponseDeadlineClosesAStalledBusinessRequest() {
        var guard = new NettyHttpRequestGuard(1024, Duration.ofMillis(20));
        var channel = new EmbeddedChannel(guard.inputDeadline(), guard, new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(final ChannelHandlerContext context, final Object message) {
                ReferenceCountUtil.release(message);
            }
            @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable failure) { }
        });
        channel.writeInbound(request("/slow"));
        channel.advanceTimeBy(21, TimeUnit.MILLISECONDS);
        channel.runScheduledPendingTasks();
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    @Test void pipelinedRequestsWaitForPreviousResponseAndCloseReleasesQueue() {
        var guard = new NettyHttpRequestGuard(1024);
        List<String> calls = new ArrayList<>();
        var application = new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(final ChannelHandlerContext context, final Object message) {
                calls.add(((DefaultFullHttpRequest) message).uri());
                ReferenceCountUtil.release(message);
            }
        };
        var channel = new EmbeddedChannel(guard.inputDeadline(), guard, application);
        var first = request("/first");
        var second = request("/second");
        var third = request("/third");
        channel.writeInbound(first, second, third);
        assertEquals(List.of("/first"), calls);
        channel.writeOutbound(new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, HttpResponseStatus.OK));
        channel.runPendingTasks();
        assertEquals(List.of("/first", "/second"), calls);
        channel.finishAndReleaseAll();
        assertEquals(0, third.refCnt());
    }

    @Test void incompleteRequestExpiresDespiteIncomingBytes() {
        var guard = new NettyHttpRequestGuard(1024);
        var channel = new EmbeddedChannel(guard.inputDeadline(), new ChannelInboundHandlerAdapter() {
            @Override public void channelRead(final ChannelHandlerContext context, final Object message) {
                ReferenceCountUtil.release(message);
            }
            @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable failure) { }
        });
        for (int second = 0; second < 31; second++) {
            channel.advanceTimeBy(1, TimeUnit.SECONDS);
            channel.writeInbound(io.netty.buffer.Unpooled.wrappedBuffer(new byte[] {1}));
            channel.runScheduledPendingTasks();
            if (!channel.isActive()) break;
        }
        assertFalse(channel.isActive());
        channel.finishAndReleaseAll();
    }

    private static DefaultFullHttpRequest request(final String path) {
        return new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, path);
    }
}
