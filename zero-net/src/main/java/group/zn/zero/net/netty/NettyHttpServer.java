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
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.security.SecurityMetadataHttpCodec;
import group.zn.zero.security.SecurityMetadataSnapshot;
import group.zn.zero.security.SecurityMetadataVerifier;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.channel.socket.SocketChannel;
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

    /** Received security metadata verifier. */
    private final SecurityMetadataVerifier securityMetadataVerifier;
    /** 请求级 metadata 校验的独立有界超时，不阻塞 EventLoop。 */
    private final java.time.Duration metadataVerificationTimeout;

    /**
     * 实际绑定地址。
     */
    private final AtomicReference<InetSocketAddress> boundAddress = new AtomicReference<>();

    /** 组合根借用的 IO 资源；为空时每次启动创建独占组。 */
    private final NettyIoResources borrowedResources;
    /** 本次启动独占的监听与连接生命周期。 */
    private NettyServerResources resources;

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
        this(options, handler, handlerExecutor, SecurityMetadataVerifier.failClosed());
    }

    /**
     * 创建带请求级安全元数据验证器的 HTTP 服务器。
     * @param options 服务配置；不可为空。
     * @param handler 业务处理器；不可为空。
     * @param handlerExecutor 外部管理的业务执行器；不可为空。
     * @param securityMetadataVerifier 校验签名、assertion 引用和有效期的验证器；不可为空。
     * @throws NullPointerException 必填参数为空时抛出；构造不启动线程。
     */
    public NettyHttpServer(
            final ServerOptions options,
            final HttpRequestHandler handler,
            final Executor handlerExecutor,
            final SecurityMetadataVerifier securityMetadataVerifier) {
        this(options, handler, handlerExecutor, securityMetadataVerifier, null);
    }

    /**
     * 创建借用 IO 组的 HTTP 服务；构造不启动线程，停止时只关闭本服务连接。
     * @param options HTTP 配置，transport 必须匹配借用组。
     * @param handler 业务处理器，不可为空。
     * @param handlerExecutor 受管执行器，不可为空。
     * @param securityMetadataVerifier 安全校验器，不可为空。
     * @param ioResources 借用组；为空时创建独占组，非空由调用方负责关闭。
     * @throws NullPointerException 必填参数为空。
     * @throws IllegalArgumentException 配置类型不是 HTTP。
     */
    public NettyHttpServer(final ServerOptions options, final HttpRequestHandler handler,
            final Executor handlerExecutor, final SecurityMetadataVerifier securityMetadataVerifier,
            final NettyIoResources ioResources) {
        this(options, handler, handlerExecutor, securityMetadataVerifier, ioResources, java.time.Duration.ofSeconds(3));
    }

    /**
     * 创建显式校验超时的 HTTP 服务；不创建线程，IO 资源关闭责任与其他构造器一致。
     * @param options HTTP 配置。 @param handler 业务 handler。 @param handlerExecutor 受管业务执行器。
     * @param securityMetadataVerifier 异步校验器。 @param ioResources 借用 IO 组；可为空。
     * @param metadataVerificationTimeout 正数校验超时；必须可表示为纳秒。
     * @throws IllegalArgumentException 配置、超时非法。 @throws NullPointerException 必填参数为空。
     */
    public NettyHttpServer(final ServerOptions options, final HttpRequestHandler handler,
            final Executor handlerExecutor, final SecurityMetadataVerifier securityMetadataVerifier,
            final NettyIoResources ioResources, final java.time.Duration metadataVerificationTimeout) {
        this.metadataVerificationTimeout = Objects.requireNonNull(metadataVerificationTimeout, "verificationTimeout");
        if (metadataVerificationTimeout.isZero() || metadataVerificationTimeout.isNegative()) {
            throw new IllegalArgumentException("metadata verification timeout must be positive");
        }
        metadataVerificationTimeout.toNanos();
        this.borrowedResources = ioResources;
        this.options = Objects.requireNonNull(options, "options");
        if (options.serverType() != ServerType.HTTP) {
            throw new IllegalArgumentException("NettyHttpServer only supports HTTP options");
        }
        this.handler = Objects.requireNonNull(handler, "handler");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.securityMetadataVerifier = Objects.requireNonNull(
                securityMetadataVerifier, "securityMetadataVerifier");
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
        try {
            NettyServerResources current = new NettyServerResources(options, borrowedResources);
            resources = current;
            ServerBootstrap bootstrap = new ServerBootstrap()
                    .group(current.io().boss(), current.io().workers())
                    .channel(NettyTransportFactory.serverChannel(options.tuning().transport()))
                    .option(ChannelOption.SO_BACKLOG, options.tuning().backlog())
                    .childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new io.netty.channel.WriteBufferWaterMark(
                            options.tuning().writeLowWaterMark(), options.tuning().writeHighWaterMark()))
                    .childOption(ChannelOption.TCP_NODELAY, true)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(final SocketChannel channel) {
                            current.track(channel);
                            NettyHttpRequestGuard guard = new NettyHttpRequestGuard(
                                    options.maxFrameLength(), options.httpRequestTimeout());
                            channel.pipeline()
                                    .addLast("httpInputDeadline", guard.inputDeadline())
                                    .addLast("httpCodec", new HttpServerCodec())
                                    .addLast("aggregator", new HttpObjectAggregator(options.maxFrameLength()))
                                    .addLast("httpAdmission", guard)
                                    .addLast("httpHandler", new NettyHttpChannelHandler(
                                            handler, handlerExecutor, securityMetadataVerifier, metadataVerificationTimeout))
                                    .addLast("httpFailures", new HttpFailureObserver());
                        }
                    });
            Channel serverChannel = current.bind(bootstrap.bind(options.host(), options.port()));
            boundAddress.set((InetSocketAddress) serverChannel.localAddress());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            cleanupAfterFailure(ex);
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty http server interrupted", ex);
        } catch (Exception ex) {
            cleanupAfterFailure(ex);
            throw ZeroException.of(NetErrorCode.START_FAILED, "start netty http server failed", ex);
        } catch (Error failure) {
            cleanupAfterFailure(failure);
            throw failure;
        }
    }

    /**
     * 停止 HTTP 服务器。
     *
     * @throws ZeroException 停止失败时抛出，必须绑定 ErrorCode。
     */
    @Override
    protected void doStop() {
        try { shutdownGroups(); }
        finally { boundAddress.set(null); }
    }

    /** 启动清理保留原始异常，资源清理失败作为次要诊断。 */
    private void cleanupAfterFailure(final Throwable failure) {
        try { shutdownGroups(); }
        catch (RuntimeException | Error cleanupFailure) {
            if (cleanupFailure != failure) failure.addSuppressed(cleanupFailure);
        } finally { boundAddress.set(null); }
    }

    private void shutdownGroups() {
        if (resources != null) {
            NettyServerResources current = resources;
            resources = null;
            current.close();
        }
    }

    /** HTTP 终端异常观察点；只记录稳定错误码，避免泄露业务异常文本。 */
    private static final class HttpFailureObserver extends io.netty.channel.ChannelInboundHandlerAdapter {
        /** 业务响应已处理，诊断不再传播到 Netty tail。 */
        @Override public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
            String code = cause instanceof ZeroException error ? error.errorCode().code() : NetErrorCode.HANDLER_FAILED.code();
            System.getLogger(NettyHttpServer.class.getName()).log(System.Logger.Level.ERROR, code);
        }
    }

    /**
     * Netty HTTP 请求处理器。
     *
     * @author zn
     */
    static final class NettyHttpChannelHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

        /**
         * HTTP 请求处理器。
         */
        private final HttpRequestHandler handler;

        /**
         * 业务执行器。
         */
        private final Executor handlerExecutor;

        /** Received security metadata verifier. */
        private final SecurityMetadataVerifier securityMetadataVerifier;
    /** 请求级 metadata 校验的独立有界超时，不阻塞 EventLoop。 */
    private final java.time.Duration metadataVerificationTimeout;

        NettyHttpChannelHandler(
                final HttpRequestHandler handler,
                final Executor handlerExecutor,
                final SecurityMetadataVerifier securityMetadataVerifier) {
            this(handler, handlerExecutor, securityMetadataVerifier, java.time.Duration.ofSeconds(3));
        }

        NettyHttpChannelHandler(final HttpRequestHandler handler, final Executor handlerExecutor,
                final SecurityMetadataVerifier securityMetadataVerifier, final java.time.Duration timeout) {
            this.metadataVerificationTimeout = timeout;
            this.handler = Objects.requireNonNull(handler, "handler");
            this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
            this.securityMetadataVerifier = Objects.requireNonNull(
                    securityMetadataVerifier, "securityMetadataVerifier");
        }

        @Override
        protected void channelRead0(final ChannelHandlerContext context, final FullHttpRequest request) {
            if (hasDuplicateHeaders(request.headers())) {
                writeUnauthorized(context);
                return;
            }
            HttpRequest zeroRequest = toZeroRequest(request);
            if (request.headers().getAll(SecurityMetadataHttpCodec.HEADER).size() > 1) {
                writeUnauthorized(context);
                return;
            }
            String encoded = request.headers().get(SecurityMetadataHttpCodec.HEADER);
            try {
                handlerExecutor.execute(() -> verifyAndSubmit(context, zeroRequest, encoded));
            } catch (RuntimeException | Error ex) {
                writeFailure(context, ex);
            }
        }

        private void verifyAndSubmit(
                final ChannelHandlerContext context, final HttpRequest request, final String encoded) {
            try {
                if (encoded == null) {
                    invokeHandler(context, request, null);
                    return;
                }
                SecurityMetadataSnapshot snapshot = SecurityMetadataHttpCodec.decode(encoded);
                if (snapshot.expired(java.time.Instant.now()) || snapshot.issuedAt().isAfter(java.time.Instant.now())) {
                    writeUnauthorized(context);
                    return;
                }
                verifyWithinDeadline(context, request, snapshot);
            } catch (RuntimeException invalidMetadata) {
                writeUnauthorized(context);
            } catch (Error failure) {
                writeFailure(context, ZeroException.of(NetErrorCode.HANDLER_FAILED,
                        "HTTP security verifier failed", failure));
            }
        }

        /** 独立完成屏障处理超时/校验竞争；回调只在 EventLoop 提交一次结果。 */
        private void verifyWithinDeadline(final ChannelHandlerContext context,
                final HttpRequest request, final SecurityMetadataSnapshot snapshot) {
            var bounded = new java.util.concurrent.CompletableFuture<SecurityContext>();
            var timeout = context.executor().schedule(
                    () -> bounded.completeExceptionally(new java.util.concurrent.TimeoutException("metadata verification")),
                    metadataVerificationTimeout.toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS);
            bounded.whenComplete((identity, failure) -> {
                timeout.cancel(false);
                context.executor().execute(() -> {
                    if (!context.channel().isActive()) return;
                    if (failure != null || identity == null || identity.expired(java.time.Instant.now())) {
                        writeUnauthorized(context);
                    } else {
                        submitHandler(context, request, identity);
                    }
                });
            });
            try {
                CompletionStage<SecurityContext> stage = Objects.requireNonNull(
                        securityMetadataVerifier.verify(snapshot, java.time.Instant.now()), "verification stage");
                stage.whenComplete((identity, failure) -> {
                    if (failure == null) bounded.complete(identity);
                    else bounded.completeExceptionally(failure);
                });
            } catch (RuntimeException | Error failure) {
                bounded.completeExceptionally(failure);
            }
        }

        private void submitHandler(
                final ChannelHandlerContext context, final HttpRequest request, final SecurityContext securityContext) {
            try {
                handlerExecutor.execute(() -> invokeHandler(context, request, securityContext));
            } catch (RuntimeException | Error ex) {
                writeFailure(context, ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "submit HTTP net handler failed",
                        ex));
            }
        }

        private void writeUnauthorized(final ChannelHandlerContext context) {
            context.executor().execute(() -> writeResponse(
                    context, HttpResponse.text(401, "unauthorized")));
        }

        private HttpRequest toZeroRequest(final FullHttpRequest request) {
            byte[] body = new byte[request.content().readableBytes()];
            request.content().getBytes(request.content().readerIndex(), body);
            Map<String, String> headers = new LinkedHashMap<>();
            HttpHeaders nettyHeaders = request.headers();
            nettyHeaders.forEach(entry -> headers.put(entry.getKey(), entry.getValue()));
            return new HttpRequest(request.method().name(), request.uri(), headers, body);
        }

        private boolean hasDuplicateHeaders(final HttpHeaders headers) {
            java.util.Set<String> names = new java.util.HashSet<>();
            for (Map.Entry<String, String> entry : headers) {
                String normalized = entry.getKey().toLowerCase(java.util.Locale.ROOT);
                if (!names.add(normalized)) {
                    return true;
                }
            }
            return false;
        }

        private void invokeHandler(
                final ChannelHandlerContext context, final HttpRequest request, final SecurityContext securityContext) {
            try {
                CompletionStage<HttpResponse> stage = securityContext == null
                        ? Objects.requireNonNull(handler.handle(request), "handlerStage")
                        : SecurityContextBridge.with(securityContext,
                                () -> Objects.requireNonNull(handler.handle(request), "handlerStage"));
                stage.whenComplete((response, cause) -> {
                    if (cause != null) {
                        writeFailure(context, cause);
                        return;
                    }
                    context.executor().execute(() -> writeResponseSafely(context, response));
                });
            } catch (RuntimeException | Error ex) {
                writeFailure(context, ZeroException.of(
                        NetErrorCode.HANDLER_FAILED,
                        "HTTP net handler failed",
                        ex));
            }
        }

        private void writeResponseSafely(final ChannelHandlerContext context, final HttpResponse response) {
            try {
                writeResponse(context, response);
            } catch (RuntimeException | Error ex) {
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
            context.writeAndFlush(nettyResponse).addListener(future -> {
                if (!future.isSuccess()) {
                    ZeroException failure = ZeroException.of(NetErrorCode.SEND_FAILED,
                            "write HTTP response failed", future.cause());
                    try {
                        // 先传播错误，让后继观察器仍能收到；无论观察器是否抛错都关闭连接。
                        context.fireExceptionCaught(failure);
                    } finally {
                        context.close();
                    }
                }
            });
        }
    }
}
