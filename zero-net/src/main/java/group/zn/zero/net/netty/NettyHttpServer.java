package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.net.IServer;
import group.zn.zero.net.ServerOptions;
import group.zn.zero.net.ServerType;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.http.HttpRequest;
import group.zn.zero.net.http.HttpRequestHandler;
import group.zn.zero.net.http.HttpResponse;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpHeaders;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.HttpVersion;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Netty HTTP 最小服务器。
 *
 * @author zn
 */
public final class NettyHttpServer extends AbstractLifecycle implements IServer {

    /**
     * 服务器配置。
     */
    private final ServerOptions options;

    /**
     * HTTP 请求处理器。
     */
    private final HttpRequestHandler handler;

    /**
     * 业务执行器。
     */
    private final Executor handlerExecutor;

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
     * 创建 HTTP 服务器。
     *
     * @param options 服务器配置；不可为空。
     * @param handler HTTP 请求处理器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空；调用方负责生命周期。
     */
    public NettyHttpServer(
            final ServerOptions options,
            final HttpRequestHandler handler,
            final Executor handlerExecutor) {
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.HTTP) {
            throw new IllegalArgumentException("NettyHttpServer only supports HTTP options");
        }
        this.handler = Objects.requireNonNull(handler, "handler");
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
     * @return HTTP；不可为空；线程安全。
     */
    @Override
    public ServerType serverType() {
        return ServerType.HTTP;
    }

    /**
     * 启动 HTTP 服务器。
     *
     * @throws ZeroException 启动失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStart() {
        bossGroup = new NioEventLoopGroup(options.bossThreads());
        workerGroup = new NioEventLoopGroup(options.workerThreads());
        try {
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(bossGroup, workerGroup)
                    .channel(NioServerSocketChannel.class)
                    .option(ChannelOption.SO_BACKLOG, 128)
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel channel) {
                            channel.pipeline()
                                    .addLast("httpCodec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(options.maxFrameLength()))
                                    .addLast("httpHandler", new NettyHttpChannelHandler(handler, handlerExecutor));
                        }
                    });
            serverChannel = bootstrap.bind(options.host(), options.port()).sync().channel();
            boundAddress.set((InetSocketAddress) serverChannel.localAddress());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            shutdownGroups();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty http server interrupted", ex);
        } catch (RuntimeException ex) {
            shutdownGroups();
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty http server failed", ex);
        }
    }

    /**
     * 停止 HTTP 服务器。
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
            throw ZeroException.of(NetErrorCode.STOP_FAILED, "stop netty http server interrupted", ex);
        } catch (RuntimeException ex) {
            throw ZeroException.of(NetErrorCode.STOP_FAILED, "stop netty http server failed", ex);
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

    /**
     * Netty HTTP 请求处理器。
     *
     * @author zn
     */
    private static final class NettyHttpChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        /**
         * HTTP 请求处理器。
         */
        private final HttpRequestHandler handler;

        /**
         * 业务执行器。
         */
        private final Executor handlerExecutor;

        NettyHttpChannelHandler(final HttpRequestHandler handler, final Executor handlerExecutor) {
            this.handler = Objects.requireNonNull(handler, "handler");
            this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext context, final FullHttpRequest request) {
            HttpRequest zeroRequest = toZeroRequest(request);
            try {
                handlerExecutor.execute(() -> invokeHandler(context, zeroRequest));
            } catch (RuntimeException ex) {
                writeFailure(context, ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "submit HTTP net handler failed",
                        ex));
            }
        }

        private HttpRequest toZeroRequest(final FullHttpRequest request) {
            byte[] body = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), body);
            Map<String, String> headers = new LinkedHashMap<>();
            HttpHeaders nettyHeaders = request.headers();
            nettyHeaders.forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
            return new HttpRequest(request.method().name(), request.uri(), headers, body);
        }

        private void invokeHandler(final ChannelHandlerContext context, final HttpRequest request) {
            try {
                CompletionStage<HttpResponse> stage = Objects.requireNonNull(
                        handler.handle(request),
                        "handlerStage");
                stage.whenComplete((response, cause) -> {
                    if (cause != null) {
                        writeFailure(context, cause);
                        return;
                    }
                    context.executor().execute(() -> writeResponseSafely(context, response));
                });
            } catch (RuntimeException ex) {
                writeFailure(context, ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "HTTP net handler failed",
                        ex));
            }
        }

        private void writeResponseSafely(final ChannelHandlerContext context, final HttpResponse response) {
            try {
                writeResponse(context, response);
            } catch (RuntimeException ex) {
                writeFailure(context, ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "write HTTP response failed",
                        ex));
            }
        }

        private void writeFailure(final ChannelHandlerContext context, final Throwable cause) {
            context.executor().execute(() -> {
                writeResponse(context, HttpResponse.text(500, "internal server error"));
                context.fireExceptionCaught(cause);
            });
        }

        private void writeResponse(final ChannelHandlerContext context, final HttpResponse response) {
            HttpResponse current = Objects.requireNonNull(response, "response");
            FullHttpResponse nettyResponse = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1,
                    HttpResponseStatus.valueOf(current.status()),
                    Unpooled.wrappedBuffer(current.body()));
            current.headers().forEach((key, value) -> nettyResponse.headers().set(key, value));
            nettyResponse.headers().set(HttpHeaderNames.CONTENT_LENGTH, nettyResponse.content().readableBytes());
            context.writeAndFlush(nettyResponse);
        }
    }
}
