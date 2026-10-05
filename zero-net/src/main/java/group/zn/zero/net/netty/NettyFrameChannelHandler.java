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
import java.util.ArrayDeque;
import java.util.List;
import java.util.Objects;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
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

    /** 仅启用 TCP 顺序模式时使用的单连接待处理帧队列；仅 EventLoop 访问。 */
    private final Queue<AcceptedFrame> orderedFrames = new ArrayDeque<>();

    /** 是否已有一个顺序帧正在执行业务或等待响应写入完成；仅 EventLoop 访问。 */
    private boolean orderedFrameActive;

    /** Channel 是否仍处于 active 状态；仅 EventLoop 访问。 */
    private boolean channelActive;

    /**
     * 是否已经向业务监听器发出 onOpen。
     */
    private boolean listenerOpened;

    /** Production lifecycle is delayed until an optional TLS handshake succeeds. */
    private boolean productionStarted;

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
        channelActive = true;
        connection = new NettyConnection(context.channel().id().asLongText(), context.channel(), codec, options, outboundBudget);
        io.netty.handler.ssl.SslHandler sslHandler = context.pipeline().get(io.netty.handler.ssl.SslHandler.class);
        if (productionLifecycle != null) {
            productionSession = new NettyProductionLifecycleSession(
                    context,
                    connection,
                    productionLifecycle,
                    frame -> submitAcceptedFrame(context, frame),
                    this::openListener,
                    cause -> notifyListenerException(context, cause));
        }
        if (sslHandler != null) {
            sslHandler.handshakeFuture().addListener(future -> {
                if (future.isSuccess()) {
                    connection.attributes().put(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED, Boolean.TRUE);
                    // The handshake callback already runs on this channel's EventLoop. Start the
                    // lifecycle inline so the first decrypted frame cannot overtake initialization.
                    startProductionLifecycle();
                } else {
                    context.close();
                }
            });
        }
        if (productionLifecycle == null) {
            openListener();
        } else if (sslHandler == null) {
            startProductionLifecycle();
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
        channelActive = false;
        orderedFrames.clear();
        orderedFrameActive = false;
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
        if (productionLifecycle != null && (!productionStarted
                || productionSession == null || !productionSession.onFrame(frame))) {
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
        if (preserveTcpFrameOrder()) {
            orderedFrames.add(new AcceptedFrame(context, frame, admittedIdentity()));
            submitNextOrderedFrame();
            return;
        }
        submitConcurrentFrame(context, frame, admittedIdentity());
    }

    /**
     * 使用已有并发模型投递单个业务帧。
     */
    private void submitConcurrentFrame(
            final ChannelHandlerContext context,
            final ProtocolFrame frame,
            final SecurityContext admittedIdentity) {
        try {
            handlerExecutor.execute(() -> invokeHandler(context, frame, admittedIdentity));
        } catch (RuntimeException | Error ex) {
            completeProductionFrame(context);
            ZeroException wrapped = ZeroException.of(NetErrorCode.HANDLER_FAILED, "submit net handler failed", ex);
            fireException(context, wrapped);
        }
    }

    /**
     * 启动单连接顺序队列的下一个帧。只有当前 handler 与全部响应写入完成才会推进队列。
     */
    private void submitNextOrderedFrame() {
        if (orderedFrameActive || !contextIsActive()) {
            return;
        }
        AcceptedFrame next = orderedFrames.poll();
        if (next == null) {
            return;
        }
        orderedFrameActive = true;
        try {
            handlerExecutor.execute(() -> invokeOrderedHandler(next));
        } catch (RuntimeException | Error ex) {
            failOrderedFrame(next.context(), ex);
        }
    }

    /**
     * 在业务执行器中调用顺序帧 handler，并将后续状态变更切回所属 EventLoop。
     */
    private void invokeOrderedHandler(final AcceptedFrame accepted) {
        try {
            CompletionStage<List<ProtocolFrame>> stage = invokeHandlerStage(accepted.frame(), accepted.securityContext());
            stage.whenComplete((responses, cause) -> executeOnEventLoop(
                    accepted.context(), () -> orderedHandlerCompleted(accepted, responses, cause)));
        } catch (Throwable ex) {
            executeOnEventLoop(accepted.context(), () -> failOrderedFrame(accepted.context(), ex));
        }
    }

    /**
     * 在 EventLoop 中写入顺序帧响应。写完成前不允许后续帧开始业务执行。
     */
    private void orderedHandlerCompleted(
            final AcceptedFrame accepted,
            final List<ProtocolFrame> responses,
            final Throwable cause) {
        if (!orderedFrameActive || !contextIsActive()) {
            return;
        }
        if (cause != null) {
            failOrderedFrame(accepted.context(), asHandlerException(cause));
            return;
        }
        CompletionStage<Void> sent = writeResponses(responses);
        try {
            sent.whenComplete((ignored, failure) -> executeOnEventLoop(
                    accepted.context(), () -> orderedResponseCompleted(accepted.context(), failure)));
        } catch (RuntimeException | Error ex) {
            failOrderedFrame(accepted.context(), asHandlerException(ex));
        }
    }

    /**
     * 释放顺序帧预算后推进下一帧，保持同一连接的业务和响应因果顺序。
     */
    private void orderedResponseCompleted(final ChannelHandlerContext context, final Throwable cause) {
        if (!orderedFrameActive) {
            return;
        }
        if (cause != null) {
            failOrderedFrame(context, asHandlerException(cause));
            return;
        }
        completeProductionFrame(context);
        orderedFrameActive = false;
        submitNextOrderedFrame();
    }

    /**
     * 终止顺序队列，避免失败连接在 close 回调之前继续执行业务帧。
     */
    private void failOrderedFrame(final ChannelHandlerContext context, final Throwable cause) {
        completeProductionFrame(context);
        orderedFrames.clear();
        orderedFrameActive = false;
        fireException(context, asHandlerException(cause));
    }

    private void invokeHandler(final ChannelHandlerContext context, final ProtocolFrame frame,
            final SecurityContext securityContext) {
        try {
            CompletionStage<List<ProtocolFrame>> stage = invokeHandlerStage(frame, securityContext);
            stage.whenComplete((responses, cause) -> {
                completeProductionFrame(context);
                if (cause != null) {
                    fireException(context, asHandlerException(cause));
                    return;
                }
                try {
                    writeResponses(responses).whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            fireException(context, asHandlerException(failure));
                        }
                    });
                } catch (RuntimeException | Error ex) {
                    fireException(context, asHandlerException(ex));
                }
            });
        } catch (Throwable ex) {
            completeProductionFrame(context);
            fireException(context, asHandlerException(ex));
        }
    }

    /**
     * 调用业务 handler 并校验帧捕获时的认证上下文仍然有效。
     */
    private CompletionStage<List<ProtocolFrame>> invokeHandlerStage(
            final ProtocolFrame frame,
            final SecurityContext securityContext) {
        if (securityContext != null && (securityContext.expired(java.time.Instant.now())
                || securityContext != connection.attributes()
                .get(group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes.SECURITY_CONTEXT).orElse(null))) {
            throw ZeroException.of(NetErrorCode.UNAUTHENTICATED, "frame authentication changed before execution", null);
        }
        return securityContext == null
                ? Objects.requireNonNull(frameHandler.handle(connection, frame), "handlerStage")
                : SecurityContextBridge.with(securityContext,
                        () -> Objects.requireNonNull(frameHandler.handle(connection, frame), "handlerStage"));
    }

    private void completeProductionFrame(final ChannelHandlerContext context) {
        if (productionSession == null) {
            return;
        }
        context.executor().execute(productionSession::onFrameCompleted);
    }

    private CompletionStage<Void> writeResponses(final List<ProtocolFrame> responses) {
        try {
            Objects.requireNonNull(responses, "responses");
            return Objects.requireNonNull(responses.size() == 1
                            ? connection.sendFrame(Objects.requireNonNull(responses.getFirst(), "response"))
                            : connection.sendFrames(List.copyOf(responses)),
                    "responseWriteStage");
        } catch (RuntimeException | Error ex) {
            return CompletableFuture.failedStage(asHandlerException(ex));
        }
    }

    /** 返回当前帧接收时的认证身份，避免业务投递期间被后续身份变更替换。 */
    private SecurityContext admittedIdentity() {
        return connection.attributes()
                .get(group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes.SECURITY_CONTEXT).orElse(null);
    }

    /** 当前 server 是否明确启用了单连接 TCP 顺序模式。 */
    private boolean preserveTcpFrameOrder() {
        return options.serverType() == group.zn.zero.net.ServerType.TCP
                && options.tuning().preserveTcpFrameOrder();
    }

    /** Channel 未关闭时才允许顺序队列继续推进。 */
    private boolean contextIsActive() {
        return channelActive && connection != null;
    }

    /** 将外部 CompletionStage 回调切回当前 Channel EventLoop。 */
    private void executeOnEventLoop(final ChannelHandlerContext context, final Runnable task) {
        if (context.executor().inEventLoop()) {
            task.run();
        } else {
            context.executor().execute(task);
        }
    }

    private ZeroException asHandlerException(final Throwable cause) {
        if (cause instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(NetErrorCode.HANDLER_FAILED, "net handler failed", cause);
    }

    private void fireException(final ChannelHandlerContext context, final Throwable cause) {
        // context.fireExceptionCaught 从下一个节点开始，会绕过本处理器的监听和关闭逻辑。
        context.executor().execute(() -> exceptionCaught(context, cause));
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

    private void startProductionLifecycle() {
        if (productionStarted || productionSession == null) {
            return;
        }
        productionStarted = true;
        productionSession.start();
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

    /** 已通过 lifecycle 门控、等待业务执行的单个帧；仅所属 EventLoop 创建和消费。 */
    private record AcceptedFrame(
            ChannelHandlerContext context,
            ProtocolFrame frame,
            SecurityContext securityContext) {
    }
}
