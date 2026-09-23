package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityContextBridge;
import group.zn.zero.protocol.ProtocolFrame;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;

/**
 * Netty 协议帧处理器。
 *
 * @author zn
 */
final class NettyFrameChannelHandler extends SimpleChannelInboundHandler<ProtocolFrame> {

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
     * 可选 production lifecycle；为空表示保持 local 行为。
     */
    private final ProductionNetworkLifecycle productionLifecycle;

    /**
     * 当前连接。
     */
    private NettyConnection connection;
    /** 服务级出站设置。 */
    private final group.zn.zero.net.ServerOptions options;
    /** 实际使用的 codec。 */
    private final group.zn.zero.protocol.codec.ProtocolFrameCodec codec;
    /** 所有连接共享的出站预算。 */
    private final OutboundBudget outboundBudget;

    /**
     * 单连接 production lifecycle 会话。
     */
    private NettyProductionLifecycleSession productionSession;

    /**
     * 是否已经向业务监听器发出 onOpen。
     */
    private boolean listenerOpened;

    /**
     * 创建 Netty 协议帧处理器。
     *
     * @param frameHandler 协议帧处理器；不可为空。
     * @param connectionListener 连接监听器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空。
     */
    NettyFrameChannelHandler(
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor) {
        this(frameHandler, connectionListener, handlerExecutor, null);
    }

    /**
     * 创建可选启用 production lifecycle 的 Netty 协议帧处理器。
     *
     * @param frameHandler 协议帧处理器；不可为空。
     * @param connectionListener 连接监听器；不可为空。
     * @param handlerExecutor 业务执行器；不可为空。
     * @param productionLifecycle production lifecycle；为空表示不启用。
     */
    NettyFrameChannelHandler(
            final ServerFrameHandler frameHandler,
            final ConnectionListener connectionListener,
            final Executor handlerExecutor,
            final ProductionNetworkLifecycle productionLifecycle) {
        this(frameHandler, connectionListener, handlerExecutor, productionLifecycle,
                group.zn.zero.net.ServerOptions.tcp("localhost", 0),
                new group.zn.zero.protocol.codec.ZeroBinaryFrameCodec(),
                new OutboundBudget(group.zn.zero.net.NetworkTuning.defaults().maxPendingBytesTotal()));
    }

    NettyFrameChannelHandler(final ServerFrameHandler frameHandler, final ConnectionListener connectionListener,
            final Executor handlerExecutor, final ProductionNetworkLifecycle productionLifecycle,
            final group.zn.zero.net.ServerOptions options,
            final group.zn.zero.protocol.codec.ProtocolFrameCodec codec, final OutboundBudget outboundBudget) {
        this.options = options;
        this.codec = codec;
        this.outboundBudget = outboundBudget;
        this.frameHandler = Objects.requireNonNull(frameHandler, "frameHandler");
        this.connectionListener = Objects.requireNonNull(connectionListener, "connectionListener");
        this.handlerExecutor = Objects.requireNonNull(handlerExecutor, "handlerExecutor");
        this.productionLifecycle = productionLifecycle;
    }

    /**
     * 建立连接。
     *
     * @param context Netty 上下文；不可为空。
     */
    @Override
    public void channelActive(final ChannelHandlerContext context) {
        connection = new NettyConnection(context.channel().id().asLongText(), context.channel(), codec, options, outboundBudget);
        io.netty.handler.ssl.SslHandler sslHandler = context.pipeline().get(io.netty.handler.ssl.SslHandler.class);
        if (sslHandler != null) {
            sslHandler.handshakeFuture().addListener(future -> {
                if (future.isSuccess()) {
                    connection.attributes().put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, Boolean.TRUE);
                } else {
                    context.close();
                }
            });
        }
        if (productionLifecycle == null) {
            openListener();
        } else {
            productionSession = new NettyProductionLifecycleSession(
                    context,
                    connection,
                    productionLifecycle,
                    frame -> submitAcceptedFrame(context, frame),
                    this::openListener,
                    cause -> notifyListenerException(context, cause));
            productionSession.start();
        }
        context.fireChannelActive();
    }

    /**
     * 关闭连接。
     *
     * @param context Netty 上下文；不可为空。
     */
    @Override
    public void channelInactive(final ChannelHandlerContext context) {
        if (connection != null) {
            if (productionSession != null) {
                productionSession.onChannelInactive();
            }
            if (listenerOpened) {
                safeClose(connection);
            }
        }
        context.fireChannelInactive();
    }

    /**
     * 处理协议帧。
     *
     * @param context Netty 上下文；不可为空。
     * @param frame 协议帧；不可为空。
     */
    @Override
    protected void channelRead0(final ChannelHandlerContext context, final ProtocolFrame frame) {
        if (connection == null) {
            fireException(context, ZeroException.of(
                    NetErrorCode.HANDLER_FAILED,
                    "net connection is not active",
                    null));
            return;
        }
        if (productionSession != null && !productionSession.onFrame(frame)) {
            return;
        }
        submitAcceptedFrame(context, frame);
    }

    /**
     * 传播异常并关闭连接。
     *
     * @param context Netty 上下文；不可为空。
     * @param cause 异常原因；不可为空。
     */
    @Override
    public void exceptionCaught(final ChannelHandlerContext context, final Throwable cause) {
        if (productionSession != null) {
            productionSession.onChannelError(cause);
        }
        try {
            safeException(connection, Objects.requireNonNull(cause, "cause"));
        } finally {
            context.close();
        }
    }

    private void submitAcceptedFrame(final ChannelHandlerContext context, final ProtocolFrame frame) {
        try {
            handlerExecutor.execute(() -> invokeHandler(context, frame));
        } catch (RuntimeException ex) {
            completeProductionFrame(context);
            ZeroException wrapped = ZeroException.of(NetErrorCode.HANDLER_FAILED, "submit net handler failed", ex);
            fireException(context, wrapped);
        }
    }

    private void invokeHandler(final ChannelHandlerContext context, final ProtocolFrame frame) {
        SecurityContext securityContext = connection.attributes()
                .get(group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes.SECURITY_CONTEXT)
                .orElse(null);
        try {
            CompletionStage<List<ProtocolFrame>> stage = securityContext == null
                    ? Objects.requireNonNull(frameHandler.handle(connection, frame), "handlerStage")
                    : SecurityContextBridge.with(securityContext,
                            () -> Objects.requireNonNull(frameHandler.handle(connection, frame), "handlerStage"));
            stage.whenComplete((responses, cause) -> {
                completeProductionFrame(context);
                if (cause != null) {
                    fireException(context, asHandlerException(cause));
                    return;
                }
                writeResponses(context, responses);
            });
        } catch (Throwable ex) {
            completeProductionFrame(context);
            fireException(context, asHandlerException(ex));
        }
    }

    private void completeProductionFrame(final ChannelHandlerContext context) {
        if (productionSession == null) {
            return;
        }
        context.executor().execute(productionSession::onFrameCompleted);
    }

    private void writeResponses(final ChannelHandlerContext context, final List<ProtocolFrame> responses) {
        try {
            List<ProtocolFrame> frames = List.copyOf(Objects.requireNonNull(responses, "responses"));
            connection.sendFrames(frames).exceptionally(cause -> {
                fireException(context, asHandlerException(cause));
                return null;
            });
        } catch (RuntimeException ex) {
            fireException(context, asHandlerException(ex));
        }
    }

    private ZeroException asHandlerException(final Throwable cause) {
        if (cause instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(NetErrorCode.HANDLER_FAILED, "net handler failed", cause);
    }

    private void fireException(final ChannelHandlerContext context, final Throwable cause) {
        context.executor().execute(() -> context.fireExceptionCaught(cause));
    }

    private void notifyListenerException(
            final ChannelHandlerContext context,
            final Throwable cause) {
        Objects.requireNonNull(context, "context");
        Throwable currentCause = Objects.requireNonNull(cause, "cause");
        context.executor().execute(() -> safeException(connection, currentCause));
    }

    private void safeOpen(final NettyConnection current) {
        try {
            connectionListener.onOpen(current);
        } catch (RuntimeException ex) {
            safeException(current, ex);
        }
    }

    private void openListener() {
        if (listenerOpened) {
            return;
        }
        listenerOpened = true;
        safeOpen(connection);
    }

    private void safeClose(final NettyConnection current) {
        try {
            connectionListener.onClose(current);
        } catch (RuntimeException ex) {
            safeException(current, ex);
        }
    }

    private void safeException(final NettyConnection current, final Throwable cause) {
        try {
            connectionListener.onException(current, cause);
        } catch (RuntimeException ex) {
            if (current != null) {
                current.close();
            }
            throw ZeroException.of(
                    NetErrorCode.HANDLER_FAILED,
                    "connection listener exception callback failed",
                    ex);
        }
    }
}
