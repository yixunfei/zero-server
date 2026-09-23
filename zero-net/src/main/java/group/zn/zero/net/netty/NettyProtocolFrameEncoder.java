package group.zn.zero.net.netty;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import java.util.Objects;

/**
 * zeroServer 协议帧到 Netty 字节的编码桥。
 *
 * @author zn
 */
final class NettyProtocolFrameEncoder extends MessageToByteEncoder<ProtocolFrame> {

    /**
     * 协议帧编解码器。
     */
    private final ProtocolFrameCodec frameCodec;
    /** 完整输出帧的字节上限，扩容前检查。 */
    private final int maximum;

    /**
     * 创建编码桥。
     *
     * @param frameCodec 协议帧编解码器；不可为空。
     */
    NettyProtocolFrameEncoder(final ProtocolFrameCodec frameCodec) {
        this(frameCodec, group.zn.zero.net.ServerOptions.DEFAULT_MAX_FRAME_LENGTH - 4);
    }

    NettyProtocolFrameEncoder(final ProtocolFrameCodec frameCodec, final int maximum) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.maximum = maximum;
    }

    /**
     * 编码协议帧。
     *
     * @param context Netty 上下文；不可为空。
     * @param message 协议帧；不可为空。
     * @param out 输出缓冲区；不可为空。
     */
    @Override
    protected void encode(
            final ChannelHandlerContext context,
            final ProtocolFrame message,
            final ByteBuf out) {
        int start = out.writerIndex();
        // 输出通常为空；writer 的绝对位置从 0 开始，直接使用 allocator 提供的可扩容 ByteBuf。
        if (start != 0) throw new IllegalStateException("frame encoder requires an empty output");
        int bound = frameCodec.encodedLength(message);
        int limit = bound < 0 ? maximum : Math.min(maximum, bound);
        var writer = new group.zn.zero.protocol.buffer.ZeroWriter(new NettyZeroBuffer(out, limit));
        frameCodec.encodeTo(message, writer);
        out.writerIndex(writer.writerIndex());
    }
}
