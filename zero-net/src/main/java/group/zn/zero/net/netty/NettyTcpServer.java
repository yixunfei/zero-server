package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.protocol.codec.ProtocolFrameCodec;
import group.zn.zero.protocol.codec.ZeroBinaryFrameCodec;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.handler.ssl.SslContext;
import java.net.InetSocketAddress;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty TCP 网络服务器。
 *
 * <p>当前实现用于阶段 2B 本地最小闭环。Netty IO 线程由本类内部创建和释放，
 * 业务处理通过外部传入的 handler executor 执行，后续可在 starter 装配阶段迁移到统一线程管理。
 *
 * @author zn
 */
public final class NettyTcpServer extends AbstractLifecycle implements IServer {

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

    /** 业务执行器。 */
    private final Executor handlerExecutor;

    /** 显式注入的 TLS 上下文；为空表示不由该服务器装配 TLS。 */
    private final SslContext tlsContext;

    /** 可选 production lifecycle。 */
    private final ProductionNetworkLifecycle productionLifecycle;

    /**
     * 实际绑定地址。
     */
    private final AtomicReference<InetSocketAddress> boundAddress = new AtomicReference<>();

    /**
     * boss 线程组。
     */
    private EventLoopGroup bossGroup;

    /**
     * worker 线程组。
     */
    private EventLoopGroup workerGroup;

    /**
     * 服务端 channel。
     */
    private Channel serverChannel;

    /**
     * 创建 Netty TCP 服务端。
     *
     * @param options 服务器配置；不可为空。
     * @param frameHandler 协议帧处理器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空；调用方负责生命周期。
     */
    public NettyTcpServer(
            final ServerOptions options,
            final ServerFrameHandler frameHandler,
            final Executor handlerExecutor) {
        this(options, new ZeroBinaryFrameCodec(), frameHandler, new ConnectionListener() {
        }, handlerExecutor, null);
    }

    /**
     * 创建 Netty TCP 服务端。
     *
     * @param options 服务器配置；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param frameHandler 协议帧处理器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空；调用方负责生命周期。
     */
    public NettyTcpServer(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor) {
        this(options, frameCodec, frameHandler, connectionListener, handlerExecutor, null);
    }

    /**
     * 创建显式启用 production lifecycle 的 Netty TCP 服务端。
     *
     * @param options 服务器配置；不可为空。
     * @param frameCodec 协议帧编解码器；不可为空。
     * @param frameHandler 协议帧处理器；不可为空。
     * @param connectionListener 连接监听器；不可为空；仅在 ESTABLISHED 后收到 onOpen。
     * @param handlerExecutor 业务执行器；不可为空；调用方负责生命周期。
     * @param productionLifecycle production lifecycle；不可为空；鉴权和观测执行器由调用方管理。
     */
    public NettyTcpServer(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor,
            final ProductionNetworkLifecycle productionLifecycle) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.TCP) {
            throw new IllegalArgumentException("NettyTcpServer only supports TCP options");
        }
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
        this.connectionListener = Objects.requireNonNull(connectionListener, "connectionListener");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.tlsContext = null;
        this.productionLifecycle = productionLifecycle;
    }

    /**
     * Creates a TCP server with an explicitly supplied Netty TLS context.
     * The application owns certificate loading and rotation.
     */
    public NettyTcpServer(
            final ServerOptions options,
            final ProtocolFrameCodec frameCodec,
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor,
            final ProductionNetworkLifecycle productionLifecycle,
            final SslContext tlsContext) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.TCP) {
            throw new IllegalArgumentException("NettyTcpServer only supports TCP options");
        }
        this.frameCodec = Objects.requireNonNull(frameCodec, "frameCodec");
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
        this.connectionListener = Objects.requireNonNull(connectionListener, "connectionListener");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.productionLifecycle = productionLifecycle;
        this.tlsContext = Objects.requireNonNull(tlsContext, "tlsContext");
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
     * @return TCP；不可为空；线程安全。
     */
    @Override
    public ServerType serverType() {
        return ServerType.TCP;
    }

    /**
     * 返回服务器配置。
     *
     * @return 配置；不可为空；线程安全。
     */
    public ServerOptions options() {
        return options;
    }

    /**
     * 返回可选 production lifecycle。
     *
     * @return lifecycle；不可为空；为空表示 local 兼容路径；线程安全。
     */
    public Optional<ProductionNetworkLifecycle> productionLifecycle() {
        return Optional.ofNullable(productionLifecycle);
    }

    /**
     * 启动 Netty TCP 服务。
     *
     * @throws ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStart() {
        try {
            bossGroup = NettyTransportFactory.eventLoops(options.tuning().transport(), options.bossThreads());
            workerGroup = NettyTransportFactory.eventLoops(options.tuning().transport(), options.workerThreads());
            OutboundBudget outbound = new OutboundBudget(options.tuning().maxPendingBytesTotal());
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NettyTransportFactory.serverChannel(options.tuning().transport()))
                    .option(ChannelOption.SO_BACKLOG, options.tuning().backlog())
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new io.netty.channel.WriteBufferWaterMark(
                            options.tuning().writeLowWaterMark(), options.tuning().writeHighWaterMark()))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childOption(ChannelOption.SO_KEEPALIVE, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel channel) {
                            if (tlsContext != null) {
                                channel.pipeline().addLast("ssl", tlsContext.newHandler(channel.alloc()));
                            }
                            if (options.tuning().flushConsolidationLimit() > 0) {
                                channel.pipeline().addLast("flushConsolidation", new io.netty.handler.flush.FlushConsolidationHandler(
                                        options.tuning().flushConsolidationLimit(), true));
                            }
                            channel.pipeline()
                                    .addLast("lengthDecoder", new LengthFieldBasedFrameDecoder(
                                            options.maxFrameLength(),
                                            0,
                                            4,
                                            0,
                                            4))
                                    .addLast("frameDecoder", new NettyProtocolFrameDecoder(frameCodec))
                                    .addLast("lengthEncoder", new LengthFieldPrepender(4))
                                    .addLast("frameEncoder", new NettyProtocolFrameEncoder(frameCodec, options.maxFrameLength() - 4))
                                    .addLast("frameHandler", new NettyFrameChannelHandler(
                                            frameHandler,
                                            connectionListener,
                                            handlerExecutor,
                                            productionLifecycle, options, frameCodec, outbound));
                        }
                    });
            serverChannel = bootstrap.bind(options.host(), options.port()).sync().channel();
            boundAddress.set((InetSocketAddress) serverChannel.localAddress());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty tcp server interrupted", ex);
        } catch (RuntimeException ex) {
            shutdownGroups();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty tcp server failed", ex);
        }
    }

    /**
     * 停止 Netty TCP 服务。
     *
     * @throws ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStop() {
        try {
            if (serverChannel != null) {
                serverChannel.close().sync();
            }
            shutdownGroups();
            boundAddress.set(null);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ZeroException.of(NetErrorCode.STOP_FAILED, "stop netty tcp server interrupted", ex);
        } catch (RuntimeException ex) {
            throw ZeroException.of(NetErrorCode.STOP_FAILED, "stop netty tcp server failed", ex);
        }
    }

    private void shutdownGroups() {
        if (workerGroup != null) {
            workerGroup.shutdownGracefully().syncUninterruptibly();
            workerGroup = null;
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully().syncUninterruptibly();
            bossGroup = null;
        }
    }
}
