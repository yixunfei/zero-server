package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Netty UDP 伪连接。
 *
 * <p>UDP 无连接，本对象只表示一次远端地址上下文。
 *
 * @author zn
 */
final class NettyUdpConnection implements IConnection {

    /**
     * 连接标识。
     */
    private final String connectionId;

    /**
     * Netty channel。
     */
    private final Channel channel;

    /**
     * 远端地址。
     */
    private final InetSocketAddress remoteAddress;

    /**
     * 协议帧编解码器。
     */
    private final ProtocolFrameCodec frameCodec;

    /**
     * 连接属性。
     */
    private final ConnectionAttributes attributes = new DefaultConnectionAttributes();

    /**
     * 创建 UDP 伪连接。
     *
     * @param connectionId 连接标识；不可为空。
     * @param channel Netty channel；不可为空。
     * @param remoteAddress 远端地址；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     */
    NettyUdpConnection(
            final String connectionId,
            final Channel channel,
            final InetSocketAddress remoteAddress,
            final ProtocolFrameCodec frameCodec) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.remoteAddress = Objects.requireNonNull(remoteAddress, "remoteAddress");
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
    }

    /**
     * 返回连接标识。
     *
     * @return 连接标识；不可为空；线程安全。
     */
    @Override
    public String connectionId() {
        return connectionId;
    }

    /**
     * 返回服务器类型。
     *
     * @return UDP；不可为空；线程安全。
     */
    @Override
    public ServerType serverType() {
        return ServerType.UDP;
    }

    /**
     * 返回远端地址。
     *
     * @return 远端地址；不可为空；线程安全。
     */
    @Override
    public SocketAddress remoteAddress() {
        return remoteAddress;
    }

    /**
     * 返回本地地址。
     *
     * @return 本地地址；可能为空；线程安全。
     */
    @Override
    public SocketAddress localAddress() {
        return channel.localAddress();
    }

    /**
     * 返回连接属性。
     *
     * @return 连接属性；不可为空；线程安全。
     */
    @Override
    public ConnectionAttributes attributes() {
        return attributes;
    }

    /**
     * 发送 UDP 消息。
     *
     * @param message 消息对象；必须为 `ProtocolFrame`。
     * @return 发送完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> send(final Object message) {
        if (!(message instanceof ProtocolFrame frame)) {
            return CompletableFuture.failedFuture(ZeroException.of(
                    NetErrorCode.INVALID_MESSAGE,
                    "Netty UDP connection only supports ProtocolFrame",
                    null));
        }
        CompletableFuture<Void> result = new CompletableFuture<>();
        byte[] encoded = frameCodec.encode(frame);
        ChannelFuture future = channel.writeAndFlush(new DatagramPacket(
                Unpooled.wrappedBuffer(encoded),
                remoteAddress));
        future.addListener(done -> {
            if (done.isSuccess()) {
                result.complete(null);
                return;
            }
            result.completeExceptionally(ZeroException.of(
                    NetErrorCode.SEND_FAILED,
                    "send UDP protocol frame failed",
                    done.cause()));
        });
        return result;
    }

    /**
     * 关闭 UDP 伪连接。
     *
     * @return 关闭完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> close() {
        return CompletableFuture.completedFuture(null);
    }
}
