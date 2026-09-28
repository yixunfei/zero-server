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
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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

    /** 远端地址上下文容量与空闲预算。 */
    private final UdpSessionOptions sessionOptions;

    /** 超长数据报丢弃次数。 */
    private final AtomicLong oversizedDatagrams = new AtomicLong();

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
        this(options, frameCodec, frameHandler, connectionListener, handlerExecutor,
                ioResources, UdpSessionOptions.defaults());
    }

    /** 创建有界远端地址上下文的 UDP 服务；共享 IO 组由调用方管理。 */
    public NettyUdpServer(final ServerOptions options, final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler, final ConnectionListener connectionListener,
            final Executor handlerExecutor, final NettyIoResources ioResources,
            final UdpSessionOptions sessionOptions) {
        this.borrowedResources = ioResources;
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.UDP) {
            throw new IllegalArgumentException("NettyUdpServer only supports UDP options");
        }
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
        this.connectionListener = Objects.requireNonNull(connectionListener, "connectionListener");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.sessionOptions = Objects.requireNonNull(sessionOptions, "sessionOptions");
    }

    /** @return 已丢弃的超长数据报数量；线程安全。 */
    public long oversizedDatagramCount() {
        return oversizedDatagrams.get();
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

        /** 仅在所属 EventLoop 访问，按最后访问时间排序。 */
        private final Map<InetSocketAddress, UdpPeer> peers = new LinkedHashMap<>(16, 0.75f, true);
        /** 空闲回收任务。 */
        private ScheduledFuture<?> expiryTask;

        @Override
        public void channelActive(final ChannelHandlerContext context) throws Exception {
            long interval = sessionOptions.idleTimeout().toNanos();
            expiryTask = context.executor().scheduleAtFixedRate(
                    this::expireIdlePeers, interval, interval, TimeUnit.NANOSECONDS);
            super.channelActive(context);
        }

        @Override
        public void channelInactive(final ChannelHandlerContext context) throws Exception {
            if (expiryTask != null) {
                expiryTask.cancel(false);
            }
            peers.values().forEach(peer -> safeClose(peer.connection));
            peers.clear();
            super.channelInactive(context);
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext context, final DatagramPacket packet) {
            if (packet.content().readableBytes() > options.maxFrameLength()) {
                oversizedDatagrams.incrementAndGet();
                return;
            }
            byte[] bytes = ByteBufUtil.getBytes(packet.content());
            ProtocolFrame frame;
            try {
                frame = frameCodec.decode(bytes);
            } catch (RuntimeException failure) {
                context.fireExceptionCaught(ZeroException.of(
                        NetErrorCode.INVALID_MESSAGE,
                        "decode UDP frame failed",
                        failure));
                return;
            }
            NettyUdpConnection connection = peer(context, packet.sender()).connection;
            try {
                handlerExecutor.execute(() -> invokeFrameHandler(context, connection, frame));
            } catch (RuntimeException ex) {
                context.fireExceptionCaught(ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "submit UDP net handler failed",
                        ex));
            }
        }

        private UdpPeer peer(final ChannelHandlerContext context, final InetSocketAddress sender) {
            UdpPeer existing = peers.get(sender);
            if (existing != null) {
                existing.lastSeenNanos = System.nanoTime();
                return existing;
            }
            if (peers.size() >= sessionOptions.maxSessions()) {
                Iterator<UdpPeer> iterator = peers.values().iterator();
                UdpPeer evicted = iterator.next();
                iterator.remove();
                safeClose(evicted.connection);
            }
            NettyUdpConnection connection = new NettyUdpConnection(
                    udpConnectionId(sender), context.channel(), sender, frameCodec);
            UdpPeer created = new UdpPeer(connection, System.nanoTime());
            peers.put(sender, created);
            safeOpen(connection);
            return created;
        }

        private void expireIdlePeers() {
            long now = System.nanoTime();
            Iterator<UdpPeer> iterator = peers.values().iterator();
            while (iterator.hasNext()) {
                UdpPeer peer = iterator.next();
                if (now - peer.lastSeenNanos < sessionOptions.idleTimeout().toNanos()) {
                    break;
                }
                iterator.remove();
                safeClose(peer.connection);
            }
        }

        private String udpConnectionId(final java.net.InetSocketAddress sender) {
            return "udp-" + sender.getAddress().getHostAddress() + ":" + sender.getPort();
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
                    if (cause != null) {
                        context.executor().execute(() -> context.fireExceptionCaught(cause));
                        return;
                    }
                    writeResponses(connection, responses);
                });
            } catch (RuntimeException ex) {
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

    /** 单 EventLoop 所有的地址上下文。 */
    private static final class UdpPeer {
        private final NettyUdpConnection connection;
        private long lastSeenNanos;

        UdpPeer(final NettyUdpConnection connection, final long lastSeenNanos) {
            this.connection = connection;
            this.lastSeenNanos = lastSeenNanos;
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
