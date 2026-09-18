package group.zn.zero.net.netty;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleResult;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimitScope;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.SecurityNetworkPolicy;
import group.zn.zero.security.ReplayProtection;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.protocol.ProtocolFrame;
import io.netty.channel.ChannelHandlerContext;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Objects;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 单个 Netty TCP 连接的 production lifecycle 会话。
 *
 * <p>除 observer 与鉴权任务外，所有可变状态只在连接所属 EventLoop 访问。</p>
 *
 * @author zn
 */
final class NettyProductionLifecycleSession {

    /** Netty handler 上下文。 */
    private final ChannelHandlerContext context;
    /** 传输连接。 */
    private final IConnection connection;
    /** 共享 lifecycle 装配。 */
    private final ProductionNetworkLifecycle lifecycle;
    /** 已通过门控的 frame 投递回调。 */
    private final Consumer<ProtocolFrame> readyFrameConsumer;
    /** 连接建立回调。 */
    private final Runnable establishedCallback;
    /** observer 失败回调。 */
    private final Consumer<Throwable> observerFailureCallback;
    /** 保持单连接事件提交顺序的 observer 调度器。 */
    private final OrderedObserverDispatcher observerDispatcher;
    /** 鉴权期间的有界 frame 队列。 */
    private final Queue<ProtocolFrame> pendingFrames = new ArrayDeque<>();
    /** 连接级 traceId。 */
    private final String traceId = "net-" + UUID.randomUUID();

    /** 当前生命周期状态。 */
    private ConnectionLifecycleState state = ConnectionLifecycleState.ACCEPTED;
    /** 待完成的异步安全检查数量。 */
    private int pendingSecurityChecks;
    /** 当前业务 in-flight 数量。 */
    private int inboundInFlight;
    /** 握手开始时间。 */
    private long handshakeStartedNanos;
    /** 鉴权开始时间。 */
    private long authenticationStartedNanos;
    /** 连续丢失的心跳检查次数。 */
    private int missedHeartbeats;
    /** 握手超时任务。 */
    private ScheduledFuture<?> handshakeTimeoutTask;
    /** 鉴权超时任务。 */
    private ScheduledFuture<?> authenticationTimeoutTask;
    /** 心跳检查任务。 */
    private ScheduledFuture<?> heartbeatTask;

    /**
     * 创建单连接 production lifecycle 会话。
     *
     * @param context Netty 上下文；不可为空。
     * @param connection 传输连接；不可为空。
     * @param lifecycle production lifecycle；不可为空。
     * @param readyFrameConsumer 已通过门控的 frame 回调；不可为空。
     * @param establishedCallback ESTABLISHED 回调；不可为空。
     * @param observerFailureCallback observer 失败回调；不可为空。
     */
    NettyProductionLifecycleSession(
            final ChannelHandlerContext context,
            final IConnection connection,
            final ProductionNetworkLifecycle lifecycle,
            final Consumer<ProtocolFrame> readyFrameConsumer,
            final Runnable establishedCallback,
            final Consumer<Throwable> observerFailureCallback) {
        this.context = Objects.requireNonNull(context, "context");
        this.connection = Objects.requireNonNull(connection, "connection");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.readyFrameConsumer = Objects.requireNonNull(readyFrameConsumer, "readyFrameConsumer");
        this.establishedCallback = Objects.requireNonNull(establishedCallback, "establishedCallback");
        this.observerFailureCallback = Objects.requireNonNull(observerFailureCallback, "observerFailureCallback");
        this.observerDispatcher = new OrderedObserverDispatcher(lifecycle.observerExecutor());
    }

    /**
     * 开始连接生命周期和握手超时。
     */
    void start() {
        requireEventLoop();
        handshakeStartedNanos = System.nanoTime();
        missedHeartbeats = 0;
        connection.attributes().put(ProductionNetworkConnectionAttributes.STATE, state);
        connection.attributes().put(ProductionNetworkConnectionAttributes.TRACE_ID, traceId);
        emit(
                ConnectionLifecycleEventType.CHANNEL_ACCEPTED,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L);
        if (lifecycle.config().tlsRequired()
                && !connection.attributes().get(ProductionNetworkConnectionAttributes.TLS_ESTABLISHED).orElse(false)) {
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    NetErrorCode.UNAUTHENTICATED,
                    ConnectionRejectionReason.UNAUTHENTICATED,
                    NetworkRateLimitScope.CONNECTION,
                    null);
            return;
        }
        boolean allowed;
        try {
            allowed = lifecycle.rateLimiter().allowConnection(connection);
        } catch (RuntimeException ex) {
            reject(
                    ConnectionLifecycleEventType.CHANNEL_ERROR,
                    NetErrorCode.HANDLER_FAILED,
                    ConnectionRejectionReason.INTERNAL_FAILURE,
                    NetworkRateLimitScope.CONNECTION,
                    ex);
            return;
        }
        if (!allowed) {
            reject(
                    ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED,
                    NetErrorCode.RATE_LIMITED,
                    ConnectionRejectionReason.CONNECTION_RATE_LIMITED,
                    NetworkRateLimitScope.CONNECTION,
                    null);
            return;
        }
        handshakeTimeoutTask = context.executor().schedule(
                () -> reject(
                        ConnectionLifecycleEventType.CONNECTION_REJECTED,
                        NetErrorCode.HANDSHAKE_TIMEOUT,
                        ConnectionRejectionReason.HANDSHAKE_TIMEOUT,
                        NetworkRateLimitScope.NONE,
                        null),
                lifecycle.config().handshakeTimeout().toNanos(),
                TimeUnit.NANOSECONDS);
    }

    /**
     * 处理入站 frame。
     *
     * @param frame 入站 frame；不可为空。
     * @return true 表示 frame 已占用 in-flight 预算，可以投递业务 handler。
     */
    boolean onFrame(final ProtocolFrame frame) {
        requireEventLoop();
        ProtocolFrame current = Objects.requireNonNull(frame, "frame");
        return switch (state) {
            case ACCEPTED -> {
                handleHandshake(current);
                yield false;
            }
            case HANDSHAKING, AUTHENTICATING -> {
                enqueueDuringAdmission(current);
                yield false;
            }
            case ESTABLISHED -> handleEstablishedFrame(current);
            case DRAINING, REJECTED, CLOSED -> false;
        };
    }

    /**
     * 释放一个业务 frame in-flight 预算。
     */
    void onFrameCompleted() {
        requireEventLoop();
        if (inboundInFlight > 0) {
            inboundInFlight--;
        }
    }

    /**
     * 记录 Channel 异常。
     *
     * @param cause 异常原因；不可为空。
     */
    void onChannelError(final Throwable cause) {
        requireEventLoop();
        Objects.requireNonNull(cause, "cause");
        emit(
                ConnectionLifecycleEventType.CHANNEL_ERROR,
                ConnectionLifecycleResult.FAILED,
                ConnectionRejectionReason.INTERNAL_FAILURE,
                NetworkRateLimitScope.NONE,
                NetErrorCode.HANDLER_FAILED,
                0L);
    }

    /**
     * 结束连接生命周期。
     */
    void onChannelInactive() {
        requireEventLoop();
        cancelTimers();
        pendingFrames.clear();
        inboundInFlight = 0;
        if (state != ConnectionLifecycleState.CLOSED) {
            transition(ConnectionLifecycleState.CLOSED);
        }
        emit(
                ConnectionLifecycleEventType.CHANNEL_CLOSED,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L);
    }

    private void handleHandshake(final ProtocolFrame frame) {
        transition(ConnectionLifecycleState.HANDSHAKING);
        emit(
                ConnectionLifecycleEventType.HANDSHAKE_RECEIVED,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                elapsed(handshakeStartedNanos));
        NetworkAdmissionDecision decision;
        try {
            decision = Objects.requireNonNull(
                    lifecycle.policy().validateHandshake(connection, frame),
                    "handshakeDecision");
        } catch (RuntimeException ex) {
            NetErrorCode errorCode = netErrorCode(ex, NetErrorCode.HANDSHAKE_REJECTED);
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    errorCode,
                    ConnectionRejectionReason.HANDSHAKE_REJECTED,
                    NetworkRateLimitScope.NONE,
                    ex);
            return;
        }
        if (!decision.accepted()) {
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    decision.errorCode(),
                    decision.rejectionReason(),
                    NetworkRateLimitScope.NONE,
                    null);
            return;
        }
        cancel(handshakeTimeoutTask);
        emit(
                ConnectionLifecycleEventType.HANDSHAKE_SUCCEEDED,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                elapsed(handshakeStartedNanos));
        transition(ConnectionLifecycleState.AUTHENTICATING);
        authenticationStartedNanos = System.nanoTime();
        emit(
                ConnectionLifecycleEventType.AUTH_REQUESTED,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L);
        authenticationTimeoutTask = context.executor().schedule(
                () -> reject(
                        ConnectionLifecycleEventType.CONNECTION_REJECTED,
                        NetErrorCode.AUTHENTICATION_TIMEOUT,
                        ConnectionRejectionReason.AUTHENTICATION_TIMEOUT,
                        NetworkRateLimitScope.NONE,
                        null),
                lifecycle.config().authenticationTimeout().toNanos(),
                TimeUnit.NANOSECONDS);
        submitAuthentication(frame);
    }

    private void submitAuthentication(final ProtocolFrame handshakeFrame) {
        try {
            lifecycle.authenticationExecutor().execute(() -> {
                CompletionStage<NetworkAdmissionDecision> stage;
                try {
                    stage = Objects.requireNonNull(
                            lifecycle.policy().authenticate(connection, handshakeFrame),
                            "authenticationStage");
                } catch (Throwable ex) {
                    executeOnEventLoop(() -> authenticationFailed(ex));
                    return;
                }
                stage.whenComplete((decision, cause) -> executeOnEventLoop(
                        () -> authenticationCompleted(decision, cause)));
            });
        } catch (RuntimeException ex) {
            authenticationFailed(ex);
        }
    }

    private void authenticationCompleted(final NetworkAdmissionDecision decision, final Throwable cause) {
        if (state != ConnectionLifecycleState.AUTHENTICATING) {
            return;
        }
        if (cause != null) {
            authenticationFailed(cause);
            return;
        }
        NetworkAdmissionDecision current = Objects.requireNonNull(decision, "authenticationDecision");
        if (!current.accepted()) {
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    current.errorCode(),
                    current.rejectionReason(),
                    NetworkRateLimitScope.NONE,
                    null);
            return;
        }
        emit(
                ConnectionLifecycleEventType.AUTH_SUCCEEDED,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                elapsed(authenticationStartedNanos));
        submitReconnect(current.subjectId());
    }

    private void authenticationFailed(final Throwable cause) {
        executeOnEventLoop(() -> {
            if (state != ConnectionLifecycleState.AUTHENTICATING) {
                return;
            }
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    netErrorCode(cause, NetErrorCode.AUTHENTICATION_REJECTED),
                    ConnectionRejectionReason.AUTHENTICATION_REJECTED,
                    NetworkRateLimitScope.NONE,
                    cause);
        });
    }

    private void submitReconnect(final String subjectId) {
        emit(
                ConnectionLifecycleEventType.RECONNECT_REQUESTED,
                ConnectionLifecycleResult.OBSERVED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L);
        try {
            lifecycle.authenticationExecutor().execute(() -> {
                CompletionStage<Void> stage;
                try {
                    stage = Objects.requireNonNull(
                            lifecycle.policy().coordinateReconnect(
                                    connection,
                                    subjectId,
                                    lifecycle.config().reconnectWindow()),
                            "reconnectStage");
                } catch (Throwable ex) {
                    executeOnEventLoop(() -> reconnectCompleted(subjectId, ex));
                    return;
                }
                stage.whenComplete((ignored, cause) -> executeOnEventLoop(
                        () -> reconnectCompleted(subjectId, cause)));
            });
        } catch (RuntimeException ex) {
            reconnectCompleted(subjectId, ex);
        }
    }

    private void reconnectCompleted(final String subjectId, final Throwable cause) {
        if (state != ConnectionLifecycleState.AUTHENTICATING) {
            return;
        }
        if (cause != null) {
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    NetErrorCode.RECONNECT_FAILED,
                    ConnectionRejectionReason.RECONNECT_FAILED,
                    NetworkRateLimitScope.NONE,
                    cause);
            return;
        }
        cancel(authenticationTimeoutTask);
        if (!subjectId.isBlank()) {
            connection.attributes().put(ProductionNetworkConnectionAttributes.SUBJECT_ID, subjectId);
        }
        emit(
                ConnectionLifecycleEventType.RECONNECT_COMPLETED,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                elapsed(authenticationStartedNanos));
        transition(ConnectionLifecycleState.ESTABLISHED);
        missedHeartbeats = 0;
        establishedCallback.run();
        scheduleHeartbeatCheck();
        drainPendingFrames();
    }

    private void enqueueDuringAdmission(final ProtocolFrame frame) {
        if (pendingFrames.size() + inboundInFlight >= lifecycle.config().maxInboundFrames()) {
            reject(
                    ConnectionLifecycleEventType.INBOUND_OVERFLOW,
                    NetErrorCode.INBOUND_OVERFLOW,
                    ConnectionRejectionReason.INBOUND_OVERFLOW,
                    NetworkRateLimitScope.FRAME,
                    null);
            return;
        }
        pendingFrames.add(frame);
    }

    private boolean handleEstablishedFrame(final ProtocolFrame frame) {
        boolean heartbeat;
        try {
            heartbeat = lifecycle.policy().isHeartbeat(connection, frame);
        } catch (RuntimeException ex) {
            reject(
                    ConnectionLifecycleEventType.CHANNEL_ERROR,
                    NetErrorCode.HANDLER_FAILED,
                    ConnectionRejectionReason.INTERNAL_FAILURE,
                    NetworkRateLimitScope.FRAME,
                    ex);
            return false;
        }
        if (heartbeat) {
            missedHeartbeats = 0;
            emit(
                    ConnectionLifecycleEventType.HEARTBEAT_RECEIVED,
                    ConnectionLifecycleResult.SUCCEEDED,
                    ConnectionRejectionReason.NONE,
                    NetworkRateLimitScope.NONE,
                    null,
                    0L);
            return false;
        }
        SecurityContext securityContext = null;
        if (lifecycle.policy() instanceof SecurityNetworkPolicy securityPolicy) {
            securityContext = connection.attributes()
                    .get(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT)
                    .orElse(null);
            if (securityContext == null || securityContext.expired(Instant.now())) {
                reject(
                        ConnectionLifecycleEventType.CONNECTION_REJECTED,
                        NetErrorCode.UNAUTHENTICATED,
                        ConnectionRejectionReason.UNAUTHENTICATED,
                        NetworkRateLimitScope.FRAME,
                        null);
                return false;
            }
            if (!securityContext.allows("network.request")) {
                reject(
                        ConnectionLifecycleEventType.CONNECTION_REJECTED,
                        NetErrorCode.AUTHORIZATION_DENIED,
                        ConnectionRejectionReason.AUTHORIZATION_DENIED,
                        NetworkRateLimitScope.FRAME,
                        null);
                return false;
            }
            if (pendingSecurityChecks + inboundInFlight >= lifecycle.config().maxInboundFrames()) {
                reject(
                        ConnectionLifecycleEventType.INBOUND_OVERFLOW,
                        NetErrorCode.INBOUND_OVERFLOW,
                        ConnectionRejectionReason.INBOUND_OVERFLOW,
                        NetworkRateLimitScope.FRAME,
                        null);
                return false;
            }
            CompletionStage<ReplayProtection.ReplayDecision> replayStage =
                    securityPolicy.checkReplayAsync(frame, securityContext);
            pendingSecurityChecks++;
            replayStage.toCompletableFuture()
                    .orTimeout(30L, TimeUnit.SECONDS)
                    .whenComplete((replay, cause) -> executeOnEventLoop(
                            () -> replayCompleted(frame, replay, cause)));
            return false;
        }
        return admitEstablishedFrame(frame);
    }

    private void replayCompleted(
            final ProtocolFrame frame,
            final ReplayProtection.ReplayDecision replay,
            final Throwable cause) {
        if (pendingSecurityChecks > 0) {
            pendingSecurityChecks--;
        }
        if (state != ConnectionLifecycleState.ESTABLISHED) {
            return;
        }
        if (cause != null || replay == null || replay != ReplayProtection.ReplayDecision.ACCEPTED) {
            ReplayProtection.ReplayDecision decision = replay == null
                    ? ReplayProtection.ReplayDecision.INVALID : replay;
            reject(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    decision == ReplayProtection.ReplayDecision.EXPIRED
                            ? NetErrorCode.AUTHENTICATION_EXPIRED : NetErrorCode.REPLAY_DETECTED,
                    decision == ReplayProtection.ReplayDecision.EXPIRED
                            ? ConnectionRejectionReason.AUTHENTICATION_EXPIRED
                            : ConnectionRejectionReason.REPLAY_DETECTED,
                    NetworkRateLimitScope.FRAME,
                    cause);
            return;
        }
        if (admitEstablishedFrame(frame)) {
            readyFrameConsumer.accept(frame);
        }
    }

    private boolean admitEstablishedFrame(final ProtocolFrame frame) {
        boolean allowed;
        try {
            allowed = lifecycle.rateLimiter().allowFrame(connection, frame);
        } catch (RuntimeException ex) {
            reject(
                    ConnectionLifecycleEventType.CHANNEL_ERROR,
                    NetErrorCode.HANDLER_FAILED,
                    ConnectionRejectionReason.INTERNAL_FAILURE,
                    NetworkRateLimitScope.FRAME,
                    ex);
            return false;
        }
        if (!allowed) {
            reject(
                    ConnectionLifecycleEventType.RATE_LIMIT_EXCEEDED,
                    NetErrorCode.RATE_LIMITED,
                    ConnectionRejectionReason.FRAME_RATE_LIMITED,
                    NetworkRateLimitScope.FRAME,
                    null);
            return false;
        }
        if (inboundInFlight + pendingSecurityChecks >= lifecycle.config().maxInboundFrames()) {
            reject(
                    ConnectionLifecycleEventType.INBOUND_OVERFLOW,
                    NetErrorCode.INBOUND_OVERFLOW,
                    ConnectionRejectionReason.INBOUND_OVERFLOW,
                    NetworkRateLimitScope.FRAME,
                    null);
            return false;
        }
        inboundInFlight++;
        return true;
    }

    private void drainPendingFrames() {
        ProtocolFrame frame;
        while (state == ConnectionLifecycleState.ESTABLISHED && (frame = pendingFrames.poll()) != null) {
            if (handleEstablishedFrame(frame)) {
                readyFrameConsumer.accept(frame);
            }
        }
    }

    private void scheduleHeartbeatCheck() {
        long intervalNanos = lifecycle.config().heartbeatInterval().toNanos();
        heartbeatTask = context.executor().scheduleAtFixedRate(
                this::checkHeartbeat,
                intervalNanos,
                intervalNanos,
                TimeUnit.NANOSECONDS);
    }

    private void checkHeartbeat() {
        if (state != ConnectionLifecycleState.ESTABLISHED) {
            return;
        }
        missedHeartbeats++;
        if (missedHeartbeats < lifecycle.config().allowedMissedHeartbeats()) {
            return;
        }
        emit(
                ConnectionLifecycleEventType.HEARTBEAT_TIMEOUT,
                ConnectionLifecycleResult.REJECTED,
                ConnectionRejectionReason.HEARTBEAT_TIMEOUT,
                NetworkRateLimitScope.NONE,
                NetErrorCode.HEARTBEAT_TIMEOUT,
                lifecycle.config().heartbeatTimeout().toNanos());
        reject(
                ConnectionLifecycleEventType.CONNECTION_REJECTED,
                NetErrorCode.HEARTBEAT_TIMEOUT,
                ConnectionRejectionReason.HEARTBEAT_TIMEOUT,
                NetworkRateLimitScope.NONE,
                null);
    }

    private void reject(
            final ConnectionLifecycleEventType event,
            final NetErrorCode errorCode,
            final ConnectionRejectionReason reason,
            final NetworkRateLimitScope scope,
            final Throwable cause) {
        if (state == ConnectionLifecycleState.REJECTED || state == ConnectionLifecycleState.CLOSED) {
            return;
        }
        cancelTimers();
        pendingFrames.clear();
        pendingSecurityChecks = 0;
        transition(ConnectionLifecycleState.REJECTED);
        emit(event, ConnectionLifecycleResult.REJECTED, reason, scope, errorCode, 0L);
        if (event != ConnectionLifecycleEventType.CONNECTION_REJECTED) {
            emit(
                    ConnectionLifecycleEventType.CONNECTION_REJECTED,
                    ConnectionLifecycleResult.REJECTED,
                    reason,
                    scope,
                    errorCode,
                    0L);
        }
        if (cause != null) {
            observerFailureCallback.accept(cause);
        }
        context.close();
    }

    private void transition(final ConnectionLifecycleState next) {
        if (!allowedTransition(state, next)) {
            throw ZeroException.of(
                    NetErrorCode.INVALID_LIFECYCLE_STATE,
                    "invalid network lifecycle transition: " + state + " -> " + next,
                    null);
        }
        state = next;
        connection.attributes().put(ProductionNetworkConnectionAttributes.STATE, next);
        emit(
                ConnectionLifecycleEventType.STATE_TRANSITION,
                ConnectionLifecycleResult.SUCCEEDED,
                ConnectionRejectionReason.NONE,
                NetworkRateLimitScope.NONE,
                null,
                0L);
    }

    private boolean allowedTransition(
            final ConnectionLifecycleState current,
            final ConnectionLifecycleState next) {
        return switch (current) {
            case ACCEPTED -> next == ConnectionLifecycleState.HANDSHAKING
                    || next == ConnectionLifecycleState.REJECTED
                    || next == ConnectionLifecycleState.CLOSED;
            case HANDSHAKING -> next == ConnectionLifecycleState.AUTHENTICATING
                    || next == ConnectionLifecycleState.REJECTED
                    || next == ConnectionLifecycleState.CLOSED;
            case AUTHENTICATING -> next == ConnectionLifecycleState.ESTABLISHED
                    || next == ConnectionLifecycleState.REJECTED
                    || next == ConnectionLifecycleState.CLOSED;
            case ESTABLISHED -> next == ConnectionLifecycleState.DRAINING
                    || next == ConnectionLifecycleState.REJECTED
                    || next == ConnectionLifecycleState.CLOSED;
            case DRAINING, REJECTED -> next == ConnectionLifecycleState.CLOSED;
            case CLOSED -> false;
        };
    }

    private void emit(
            final ConnectionLifecycleEventType event,
            final ConnectionLifecycleResult result,
            final ConnectionRejectionReason reason,
            final NetworkRateLimitScope scope,
            final NetErrorCode errorCode,
            final long latencyNanos) {
        ConnectionLifecycleObservation observation = new ConnectionLifecycleObservation(
                Instant.now(),
                lifecycle.config().listener(),
                "tcp",
                traceId,
                connection.connectionId(),
                maskedRemoteAddress(connection.remoteAddress()),
                state,
                event,
                result,
                reason,
                scope,
                errorCode,
                Math.max(0L, latencyNanos),
                pendingFrames.size() + inboundInFlight);
        try {
            observerDispatcher.execute(() -> {
                try {
                    lifecycle.observer().onEvent(observation);
                } catch (Throwable ex) {
                    observerFailureCallback.accept(ZeroException.of(
                            NetErrorCode.OBSERVER_FAILED,
                            "network lifecycle observer failed",
                            ex));
                }
            });
        } catch (RuntimeException ex) {
            observerFailureCallback.accept(ZeroException.of(
                    NetErrorCode.OBSERVER_FAILED,
                    "submit network lifecycle observer failed",
                    ex));
        }
    }

    /**
     * 在共享受管执行器上保持单连接 observer 事件因果顺序的轻量调度器。
     *
     * <p>每个连接最多向底层执行器提交一个活动 drain；后续事件进入当前连接私有队列并由同一
     * drain 按提交顺序执行。它不创建线程或线程池，不跨连接串行化，也不改变底层执行器生命周期。
     * 即使某个回调异常，队列仍会继续排空，避免后续 close 遥测永久滞留。</p>
     */
    private static final class OrderedObserverDispatcher implements Executor {

        /** 框架受管的共享 observer 执行器。 */
        private final Executor delegate;
        /** 单连接有序任务队列。 */
        private final Queue<Runnable> tasks = new ArrayDeque<>();
        /** 是否已有 drain 被底层执行器接受或正在运行。 */
        private boolean drainScheduled;

        private OrderedObserverDispatcher(final Executor delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        /**
         * 按单连接提交顺序排队 observer 任务。
         *
         * @param command observer 任务；不可为空。
         * @throws RuntimeException 底层执行器拒绝首个 drain 时抛出；队列会恢复为空闲状态。
         */
        @Override
        public void execute(final Runnable command) {
            Runnable current = Objects.requireNonNull(command, "command");
            boolean submitDrain;
            synchronized (tasks) {
                tasks.add(current);
                submitDrain = !drainScheduled;
                if (submitDrain) {
                    drainScheduled = true;
                }
            }
            if (!submitDrain) {
                return;
            }
            try {
                delegate.execute(this::drain);
            } catch (RuntimeException exception) {
                synchronized (tasks) {
                    tasks.clear();
                    drainScheduled = false;
                }
                throw exception;
            }
        }

        /**
         * 顺序排空当前连接的 observer 任务，并在全部任务完成后传播回调自身异常。
         */
        private void drain() {
            Throwable callbackFailure = null;
            while (true) {
                Runnable next;
                synchronized (tasks) {
                    next = tasks.poll();
                    if (next == null) {
                        drainScheduled = false;
                        break;
                    }
                }
                try {
                    next.run();
                } catch (Throwable exception) {
                    if (callbackFailure == null) {
                        callbackFailure = exception;
                    } else {
                        callbackFailure.addSuppressed(exception);
                    }
                }
            }
            rethrow(callbackFailure);
        }

        /**
         * 在 drain 完成后按原类型传播回调异常。
         *
         * @param failure 聚合异常；为空表示全部成功。
         */
        private void rethrow(final Throwable failure) {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            if (failure != null) {
                throw new IllegalStateException("observer callback failed", failure);
            }
        }
    }

    private String maskedRemoteAddress(final SocketAddress address) {
        if (!(address instanceof InetSocketAddress socketAddress)) {
            return "unknown:*";
        }
        InetAddress inetAddress = socketAddress.getAddress();
        if (inetAddress == null) {
            return "unresolved:*";
        }
        byte[] bytes = inetAddress.getAddress();
        if (inetAddress instanceof Inet6Address) {
            int part0 = (bytes[0] & 0xff) << 8 | bytes[1] & 0xff;
            int part1 = (bytes[2] & 0xff) << 8 | bytes[3] & 0xff;
            int part2 = (bytes[4] & 0xff) << 8 | bytes[5] & 0xff;
            int part3 = (bytes[6] & 0xff) << 8 | bytes[7] & 0xff;
            return Integer.toHexString(part0) + ":" + Integer.toHexString(part1) + ":"
                    + Integer.toHexString(part2) + ":" + Integer.toHexString(part3) + "::/64:*";
        }
        return (bytes[0] & 0xff) + "." + (bytes[1] & 0xff) + "." + (bytes[2] & 0xff) + ".0:*";
    }

    private void executeOnEventLoop(final Runnable task) {
        if (context.executor().inEventLoop()) {
            task.run();
        } else {
            context.executor().execute(task);
        }
    }

    private void requireEventLoop() {
        if (!context.executor().inEventLoop()) {
            throw ZeroException.of(
                    NetErrorCode.INVALID_LIFECYCLE_STATE,
                    "network lifecycle state must be changed on its EventLoop",
                    null);
        }
    }

    private long elapsed(final long startedNanos) {
        return Math.max(0L, System.nanoTime() - startedNanos);
    }

    private NetErrorCode netErrorCode(final Throwable cause, final NetErrorCode fallback) {
        if (cause instanceof ZeroException zeroException && zeroException.errorCode() instanceof NetErrorCode netCode) {
            return netCode;
        }
        return fallback;
    }

    private void cancelTimers() {
        cancel(handshakeTimeoutTask);
        cancel(authenticationTimeoutTask);
        cancel(heartbeatTask);
    }

    private void cancel(final ScheduledFuture<?> future) {
        if (future != null) {
            future.cancel(false);
        }
    }
}
