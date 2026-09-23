package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBufUtil;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.DatagramChannel;
import io.netty.channel.socket.DatagramPacket;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty UDP 最小服务器。
 *
 * @author zn
 */
public final class NettyUdpServer extends AbstractLifecycle implements IServer {

    /**
     * 服务器配置。
     */
    private final ServerOptions options;

    /**
     * 协议帧编解码器。
     */
    private final ProtocolFrameCodec frameCodec;

    /**
     * 协议帧处理器。
     */
    private final ServerFrameHandler frameHandler;

    /**
     * 连接监听器。
     */
    private final ConnectionListener connectionListener;

    /**
     * 业务执行器。
     */
    private final Executor handlerExecutor;

    /**
     * 实际绑定地址。
     */
    private final AtomicReference<InetSocketAddress> boundAddress = new AtomicReference<>();

    /** 组合根借用的 IO 资源；为空时每次启动创建独占组。 */
    private final NettyIoResources borrowedResources;
    /** 本次启动独占的监听与连接生命周期。 */
    private NettyServerResources resources;

    /**
     * 创建 UDP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param frameHandler 协议帧处理器；不可为空。
     * @param connectionListener 连接监听器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空；调用方负责生命周期。
     */
    public NettyUdpServer(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor) {
        this(options, frameCodec, frameHandler, connectionListener, handlerExecutor, null);
    }

    /**
     * 创建借用 IO 组的 UDP 服务；构造不启动线程，停止只关闭本服务 socket。
     * @param options UDP 配置，transport 必须匹配借用组。
     * @param frameCodec 编解码器，不可为空。
     * @param frameHandler 业务处理器，不可为空。
     * @param connectionListener 监听器，不可为空。
     * @param handlerExecutor 受管执行器，不可为空。
     * @param ioResources 借用组；为空时创建独占组，非空由调用方关闭。
     * @throws NullPointerException 必填参数为空。
     * @throws IllegalArgumentException 配置类型不是 UDP。
     */
    public NettyUdpServer(final ServerOptions options, final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler, final ConnectionListener connectionListener,
            final Executor handlerExecutor, final NettyIoResources ioResources) {
        this.borrowedResources = ioResources;
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.UDP) {
            throw new IllegalArgumentException("NettyUdpServer only supports UDP options");
        }
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
        this.connectionListener = Objects.requireNonNull(connectionListener, "connectionListener");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
    }

    /**
     * 返回监听地址。
     *
     * @return 监听地址；不可为空；线程安全。
     */
    @Override
    public String bindAddress() {
        InetSocketAddress current = boundAddress.get();
        if (current == null) {
            return options.bindAddress();
        }
        return current.getHostString() + ":" + current.getPort();
    }

    /**
     * 返回实际绑定端口。
     *
     * @return 端口；未启动且未绑定时返回配置端口。
     */
    public int boundPort() {
        InetSocketAddress current = boundAddress.get();
        return current == null ? options.port() : current.getPort();
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
     * 启动 UDP 服务器。
     *
     * @throws ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStart() {
        try {
            NettyServerResources current = new NettyServerResources(options, borrowedResources);
            resources = current;
            Bootstrap bootstrap = new Bootstrap()
                    .group(current.io().workers())
                    .channel(NettyTransportFactory.datagramChannel(options.tuning().transport()))
                    .handler(new ChannelInitializer<DatagramChannel>() {
                        @Override
                        protected void initChannel(final DatagramChannel current) {
                            current.pipeline().addLast("udpHandler", new UdpFrameHandler());
                        }
                    });
            Channel channel = current.bind(bootstrap.bind(options.host(), options.port()));
            boundAddress.set((InetSocketAddress) channel.localAddress());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownGroup();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty UDP server interrupted", ex);
        } catch (Exception ex) {
            shutdownGroup();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty UDP server failed", ex);
        }
    }

    /**
     * 停止 UDP 服务器。
     *
     * @throws ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStop() {
        try { shutdownGroup(); }
        finally { boundAddress.set(null); }
    }

    private void shutdownGroup() {
        if (resources != null) {
            resources.close();
            resources = null;
        }
    }

    /**
     * UDP frame 处理器。
     *
     * @author zn
     */
    private final class UdpFrameHandler extends SimpleChannelInboundHandler<DatagramPacket> {

        @Override
        protected void channelRead0(final ChannelHandlerContext context, final DatagramPacket packet) {
            byte[] bytes = ByteBufUtil.getBytes(packet.content());
            ProtocolFrame frame = frameCodec.decode(bytes);
            NettyUdpConnection connection = new NettyUdpConnection(
                    UUID.randomUUID().toString(),
                    context.channel(),
                    packet.sender(),
                    frameCodec);
            safeOpen(connection);
            try {
                handlerExecutor.execute(() -> invokeFrameHandler(context, connection, frame));
            } catch (RuntimeException ex) {
                safeClose(connection);
                context.fireExceptionCaught(ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "submit UDP net handler failed",
                        ex));
            }
        }

        @Override
        public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
            safeException(null, cause);
        }

        private void invokeFrameHandler(
                final ChannelHandlerContext context,
                final NettyUdpConnection connection,
                final ProtocolFrame frame) {
            try {
                frameHandler.handle(connection, frame).whenComplete((responses, cause) -> {
                    try {
                        if (cause != null) {
                            context.executor().execute(() -> context.fireExceptionCaught(cause));
                            return;
                        }
                        writeResponses(connection, responses);
                    } finally {
                        safeClose(connection);
                    }
                });
            } catch (RuntimeException ex) {
                safeClose(connection);
                context.executor().execute(() -> context.fireExceptionCaught(ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "UDP net handler failed",
                        ex)));
            }
        }

        private void writeResponses(final NettyUdpConnection connection, final List<ProtocolFrame> responses) {
            List<ProtocolFrame> frames = List.copyOf(Objects.requireNonNull(responses, "responses"));
            for (ProtocolFrame response : frames) {
                connection.sendFrame(response);
            }
        }
    }

    private void safeOpen(final NettyUdpConnection connection) {
        try {
            connectionListener.onOpen(connection);
        } catch (RuntimeException ex) {
            safeException(connection, ex);
        }
    }

    private void safeClose(final NettyUdpConnection connection) {
        try {
            connectionListener.onClose(connection);
        } catch (RuntimeException ex) {
            safeException(connection, ex);
        }
    }

    private void safeException(final NettyUdpConnection connection, final Throwable cause) {
        try {
            connectionListener.onException(connection, cause);
        } catch (RuntimeException ignored) {
            // 监听器异常不能反向破坏 Netty 路径。
        }
    }
}
