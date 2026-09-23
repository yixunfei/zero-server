package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionAttributes;
import group.zn.zero.net.DefaultConnectionAttributes;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.util.AttributeKey;
import java.net.SocketAddress;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Netty TCP 连接。
 *
 * @author zn
 */
public final class NettyConnection implements IConnection {

    /**
     * 连接标识。
     */
    private final String connectionId;

    /**
     * Netty channel。
     */
    private final Channel channel;

    /** 有界批次发送器；线程安全。 */
    private final NettyOutbound outbound;

    /**
     * 连接属性。
     */
    private final ConnectionAttributes attributes = new DefaultConnectionAttributes();

    /**
     * 创建 Netty 连接。
     *
     * @param connectionId 连接标识；不可为空。
     * @param channel Netty channel；不可为空。
     */
    public NettyConnection(final String connectionId, final Channel channel) {
        this(connectionId, channel, new group.zn.zero.protocol.codec.ZeroBinaryFrameCodec(),
                group.zn.zero.net.ServerOptions.tcp("localhost", 0),
                new OutboundBudget(group.zn.zero.net.NetworkTuning.defaults().maxPendingBytesTotal()));
    }

    NettyConnection(final String connectionId, final Channel channel,
            final group.zn.zero.protocol.codec.ProtocolFrameCodec codec,
            final group.zn.zero.net.ServerOptions options, final OutboundBudget budget) {
        this.connectionId = Objects.requireNonNull(connectionId, "connectionId");
        this.channel = Objects.requireNonNull(channel, "channel");
        this.outbound = new NettyOutbound(channel, codec, options, budget);
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
     * @return TCP；不可为空；线程安全。
     */
    @Override
    public ServerType serverType() {
        return ServerType.TCP;
    }

    /**
     * 返回远端地址。
     *
     * @return 远端地址；可能为空；线程安全。
     */
    @Override
    public SocketAddress remoteAddress() {
        return channel.remoteAddress();
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
     * 发送消息。
     *
     * @param message 消息对象；必须为 `ProtocolFrame`。
     * @return 发送完成信号；不可为空；线程安全。
     * @throws ZeroException 当消息类型非法或发送失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    public CompletionStage<Void> send(final Object message) {
        if (!(message instanceof ProtocolFrame frame)) {
            return CompletableFuture.failedFuture(ZeroException.of(
                    NetErrorCode.INVALID_MESSAGE,
                    "Netty TCP connection only supports ProtocolFrame",
                    null));
        }
        return outbound.send(frame);
    }

    /**
     * 批量发送可靠帧；统一准入，在 EventLoop 按列表顺序 write 后 flush 一次。
     * @param frames 有序帧列表，不可为空；方法调用期间不得并发修改。
     * @return 全部帧实际写完成的信号；线程安全，超限返回 OUTBOUND_OVERFLOW。
     */
    public CompletionStage<Void> sendFrames(final java.util.List<ProtocolFrame> frames) {
        return outbound.send(Objects.requireNonNull(frames, "frames"));
    }

    /** @return 待写资源预算占用；线程安全，包含事件循环待提交任务。 */
    public long pendingOutboundBytes() { return outbound.pendingBytes(); }

    /**
     * 关闭连接。
     *
     * @return 关闭完成信号；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> close() {
        CompletableFuture<Void> result = new CompletableFuture<>();
        ChannelFuture future = channel.close();
        future.addListener(done -> {
            if (done.isSuccess()) {
                result.complete(null);
                return;
            }
            result.completeExceptionally(ZeroException.of(
                    NetErrorCode.SEND_FAILED,
                    "close netty connection failed",
                    done.cause()));
        });
        return result;
    }
}
