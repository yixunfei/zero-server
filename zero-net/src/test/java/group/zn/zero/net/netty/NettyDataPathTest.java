package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.NetworkTransport;
import group.zn.zero.net.NetworkTuning;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufUtil;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.channels.ClosedChannelException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

/** Netty 真实 codec 桥、批次 flush、慢写预算和资源释放回归。 @author zn */
class NettyDataPathTest {
    @Test void directBuffersPreserveGoldenBytesAndFrameLifetime() {
        var codec = new ZeroBinaryFrameCodec();
        ProtocolFrame frame = new ProtocolFrame(1, 1, 0, new byte[] {3}, new byte[32768]);
        var encoder = new EmbeddedChannel(new NettyProtocolFrameEncoder(codec));
        var decoder = new EmbeddedChannel(new NettyProtocolFrameDecoder(codec));
        try {
            assertTrue(encoder.writeOutbound(frame));
            ByteBuf encoded = encoder.readOutbound();
            assertArrayEquals(codec.encode(frame), ByteBufUtil.getBytes(encoded));
            assertTrue(decoder.writeInbound(encoded));
            assertEquals(0, encoded.refCnt());
            ProtocolFrame decoded = decoder.readInbound();
            assertArrayEquals(frame.payload(), decoded.payload());
        } finally { encoder.finishAndReleaseAll(); decoder.finishAndReleaseAll(); }
    }

    @Test void customArrayCodecRetainsWorkingExtensionPath() {
        ProtocolFrame expected = new ProtocolFrame(1, 1, 0, null, new byte[] {42});
        ProtocolFrameCodec codec = new ProtocolFrameCodec() {
            @Override public byte[] encode(final ProtocolFrame frame) { return frame.payload(); }
            @Override public ProtocolFrame decode(final byte[] bytes) { return new ProtocolFrame(1, 1, 0, null, bytes); }
        };
        var channel = new EmbeddedChannel(new NettyProtocolFrameEncoder(codec), new NettyProtocolFrameDecoder(codec));
        try {
            channel.writeOutbound(expected);
            ByteBuf bytes = channel.readOutbound();
            assertArrayEquals(new byte[] {42}, ByteBufUtil.getBytes(bytes));
            channel.writeInbound(bytes);
            assertArrayEquals(expected.payload(), ((ProtocolFrame) channel.readInbound()).payload());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void batchFlushesOnceAndReleasesGlobalBudgetOnFailure() {
        HoldingWrites held = new HoldingWrites();
        var channel = new EmbeddedChannel(held);
        var options = ServerOptions.tcp("localhost", 0).withMaxFrameLength(1024).withTuning(
                new NetworkTuning(NetworkTransport.NIO, 128, 32, 64, 1000, 1000, 0));
        OutboundBudget budget = new OutboundBudget(1000);
        var connection = new NettyConnection("c", channel, new ZeroBinaryFrameCodec(), options, budget);
        ProtocolFrame frame = new ProtocolFrame(1, 1, 0, null, new byte[100]);
        try {
            var batch = connection.sendFrames(List.of(frame, frame)).toCompletableFuture();
            assertFalse(batch.isDone());
            assertEquals(1, held.flushes);
            assertTrue(budget.used() > 0);
            var failure = assertThrows(CompletionException.class, () -> connection.sendFrame(frame).toCompletableFuture().join());
            assertEquals(NetErrorCode.OUTBOUND_OVERFLOW, ((ZeroException) failure.getCause()).errorCode());
            held.promises.get(0).setSuccess();
            assertFalse(batch.isDone());
            held.promises.get(1).setFailure(new ClosedChannelException());
            assertThrows(CompletionException.class, batch::join);
            assertEquals(0, budget.used());
            assertEquals(0, connection.pendingOutboundBytes());
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void exactEncodedBoundaryWorksAndOversizeFailsWithoutLeak() {
        var codec = new ZeroBinaryFrameCodec();
        var frame = new ProtocolFrame(1, 1, 0, null, new byte[0]);
        int size = codec.encodedLength(frame);
        var channel = new EmbeddedChannel(new NettyProtocolFrameEncoder(codec, size));
        try {
            channel.writeOutbound(frame);
            ByteBuf bytes = channel.readOutbound();
            assertEquals(size, bytes.readableBytes());
            bytes.release();
            assertThrows(RuntimeException.class,
                    () -> channel.writeOutbound(new ProtocolFrame(1, 1, 0, null, new byte[100])));
        } finally { channel.finishAndReleaseAll(); }
    }

    @Test void explicitUnavailableEpollFailsAndAutoKeepsNio() {
        assertEquals(io.netty.channel.socket.nio.NioServerSocketChannel.class,
                NettyTransportFactory.serverChannel(NetworkTransport.AUTO));
        if (!io.netty.channel.epoll.Epoll.isAvailable()) {
            assertThrows(IllegalStateException.class, () -> NettyTransportFactory.serverChannel(NetworkTransport.EPOLL));
        }
    }

    /** 模拟尚未完成的内核写，不分配网络线程。 @author zn */
    private static final class HoldingWrites extends ChannelOutboundHandlerAdapter {
        /** 等待由测试完成的写承诺。 */
        private final List<ChannelPromise> promises = new ArrayList<>();
        /** 刷新次数。 */
        private int flushes;
        @Override public void write(final ChannelHandlerContext ctx, final Object message, final ChannelPromise promise) {
            io.netty.util.ReferenceCountUtil.release(message);
            promises.add(promise);
        }
        @Override public void flush(final ChannelHandlerContext ctx) { flushes++; }
    }
}
