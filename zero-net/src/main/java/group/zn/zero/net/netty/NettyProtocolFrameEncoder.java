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

    /**
     * 创建编码桥。
     *
     * @param frameCodec 协议帧编解码器；不可为空。
     */
    NettyProtocolFrameEncoder(final ProtocolFrameCodec frameCodec) {
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
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
        out.writeBytes(frameCodec.encode(message));
    }
}
