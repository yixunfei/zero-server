package group.zn.zero.net.netty;

import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToMessageDecoder;
import java.util.List;
import java.util.Objects;

/**
 * Netty 到 zeroServer 协议帧的解码桥。
 *
 * @author zn
 */
final class NettyProtocolFrameDecoder extends MessageToMessageDecoder<ByteBuf> {

    /**
     * 协议帧编解码器。
     */
    private final ProtocolFrameCodec frameCodec;

    /**
     * 创建解码桥。
     *
     * @param frameCodec 协议帧编解码器；不可为空。
     */
    NettyProtocolFrameDecoder(final ProtocolFrameCodec frameCodec) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
    }

    /**
     * 解码协议帧。
     *
     * @param context Netty 上下文；不可为空。
     * @param message 字节消息；不可为空。
     * @param out 输出列表；不可为空。
     */
    @Override
    protected void decode(
            final ChannelHandlerContext context,
            final ByteBuf message,
            final List<Object> out) {
        var reader = new group.zn.zero.protocol.buffer.ZeroReader(
                new NettyZeroBuffer(message, message.capacity()), message.readerIndex(), message.readableBytes());
        ProtocolFrame frame = frameCodec.decodeFrom(reader);
        out.add(frame);
    }
}
