package group.zn.zero.net.netty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.net.ConnectionListener;
import group.zn.zero.net.IConnection;
import group.zn.zero.net.ServerFrameHandler;
import group.zn.zero.net.error.NetErrorCode;
import group.zn.zero.net.lifecycle.ConnectionLifecycleEventType;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObservation;
import group.zn.zero.net.lifecycle.ConnectionLifecycleObserver;
import group.zn.zero.net.lifecycle.ConnectionLifecycleState;
import group.zn.zero.net.lifecycle.ConnectionRejectionReason;
import group.zn.zero.net.lifecycle.NetworkAdmissionDecision;
import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkConnectionAttributes;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.protocol.ProtocolFrame;
import group.zn.zero.security.SecurityChain;
import io.netty.channel.embedded.EmbeddedChannel;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/**
 * PNFT-01 至 PNFT-08、PNFT-10 生产网络生命周期 focused tests。
 *
 * @author zn
 */
class ProductionNetworkLifecycleFocusedTest {
    /** 首个鉴权帧也必须受入站限流，拒绝后不调用鉴权策略。 */
    @Test void admissionFrameShouldBeRateLimitedBeforeHandshake() {
        AtomicInteger handshakes = new AtomicInteger();
        NetworkRateLimiter limiter = new NetworkRateLimiter() {
            @Override public boolean allowConnection(final IConnection connection) { return true; }
            @Override public boolean allowAdmissionFrame(final IConnection connection, final ProtocolFrame frame) {
                return false;
            }
        };
        ProductionNetworkPolicy policy = (connection, frame) -> {
            handshakes.incrementAndGet();
            return NetworkAdmissionDecision.allow();
        };
        try (Fixture fixture = new Fixture(fastConfig(), policy, limiter, Runnable::run)) {
            fixture.write(frame(1, 1, "login"));
            assertEquals(0, handshakes.get());
            assertFalse(fixture.channel.isActive());
            assertTrue(fixture.observedError(NetErrorCode.RATE_LIMITED));
        }
    }
    /** 异步 replay 完成时必须复核原鉴权代际，不能以替换或过期主体执行。 */
    @Test void replayCompletionRejectsChangedIdentity() {
        for (boolean expired : new boolean[]{true, false}) {
            java.time.Instant now = java.time.Instant.now();
            var identity = new group.zn.zero.security.SecurityContext("alice", now, now.plusSeconds(60),
                    "tcp", "peer", "trusted", "trace", "session", java.util.Set.of("network.request"), java.util.Map.of());
            var replay = new CompletableFuture<group.zn.zero.security.ReplayProtection.ReplayDecision>();
            var chain = new group.zn.zero.security.SecurityChain(request -> CompletableFuture.completedFuture(
                    group.zn.zero.security.AuthenticationProvider.AuthenticationResult.accepted(identity)), request -> replay, null, false);
            var policy = new group.zn.zero.net.lifecycle.SecurityNetworkPolicy(acceptingPolicy(), chain);
            try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(), Runnable::run)) {
                fixture.write(frame(1, 1, "handshake"));
                assertTrue(fixture.listenerOpened.get());
                fixture.write(frame(2, 1, "business"));
                var replacement = new group.zn.zero.security.SecurityContext(expired ? "alice" : "bob",
                        now.minusSeconds(120), expired ? now.minusSeconds(1) : now.plusSeconds(60),
                        "tcp", "peer", "trusted", "trace", "session", java.util.Set.of("network.request"), java.util.Map.of());
                fixture.connection.get().attributes().put(ProductionNetworkConnectionAttributes.SECURITY_CONTEXT, replacement);
                replay.complete(group.zn.zero.security.ReplayProtection.ReplayDecision.ACCEPTED);
                fixture.runPending();
                assertEquals(0, fixture.businessCalls.get());
            }
        }
    }

    /**
     * PNFT-01：验证握手超时会拒绝并关闭连接，且不会打开业务 listener。
     */
    @Test
    void pnft01HandshakeTimeoutShouldRejectAndClose() {
        ProductionNetworkConfig config = fastConfig().withAdmissionTimeouts(
                Duration.ofMillis(10),
                Duration.ofSeconds(1)).withHeartbeatEnabled(false);
        try (Fixture fixture = new Fixture(config, acceptingPolicy(), NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.channel.advanceTimeBy(11, TimeUnit.MILLISECONDS);
            fixture.runPending();

            assertFalse(fixture.channel.isActive());
            assertFalse(fixture.listenerOpened.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.HANDSHAKE_TIMEOUT));
            assertTrue(fixture.observedError(NetErrorCode.HANDSHAKE_TIMEOUT));
            assertEquals(0, fixture.businessCalls.get());
        }
    }

    /**
     * PNFT-02：验证协议版本不兼容时不会进入业务 handler。
     */
    @Test
    void pnft02UnsupportedProtocolVersionShouldRejectBeforeBusiness() {
        ProductionNetworkPolicy policy = (connection, frame) -> frame.protocolVersion() == 1
                ? NetworkAdmissionDecision.allow()
                : NetworkAdmissionDecision.rejected(
                        NetErrorCode.PROTOCOL_VERSION_UNSUPPORTED,
                        ConnectionRejectionReason.PROTOCOL_VERSION_UNSUPPORTED);
        try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 2, "handshake-v2"));

            assertFalse(fixture.channel.isActive());
            assertFalse(fixture.listenerOpened.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.PROTOCOL_VERSION_UNSUPPORTED));
            assertTrue(fixture.observedError(NetErrorCode.PROTOCOL_VERSION_UNSUPPORTED));
            assertEquals(0, fixture.businessCalls.get());
        }
    }

    /**
     * PNFT-03：验证鉴权拒绝绑定权限错误且观测事件不包含握手 payload。
     */
    @Test
    void pnft03AuthenticationRejectShouldBeObservableWithoutToken() {
        ProductionNetworkPolicy policy = rejectingAuthenticationPolicy();
        try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "token-super-secret"));

            assertFalse(fixture.channel.isActive());
            assertFalse(fixture.listenerOpened.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.AUTHENTICATION_REJECTED));
            assertTrue(fixture.observedError(NetErrorCode.AUTHENTICATION_REJECTED));
            assertTrue(fixture.observations.stream()
                    .map(Object::toString)
                    .noneMatch(text -> text.contains("token-super-secret")));
        }
    }

    /**
     * PNFT-04：验证 authenticate 和 reconnect 只通过受管鉴权执行器调用，不在入站调用栈执行。
     */
    @Test
    void pnft04AuthenticationShouldUseManagedExecutor() throws InterruptedException {
        QueueExecutor executor = new QueueExecutor();
        AtomicReference<String> authenticationThread = new AtomicReference<>();
        ProductionNetworkPolicy policy = new ProductionNetworkPolicy() {
            /** 校验握手。 */
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            /** 记录鉴权线程。 */
            @Override
            public CompletionStage<NetworkAdmissionDecision> authenticate(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                authenticationThread.set(Thread.currentThread().getName());
                return CompletableFuture.completedFuture(NetworkAdmissionDecision.authenticated("player-1001"));
            }
        };
        try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(), executor)) {
            fixture.write(frame(1, 1, "handshake"));

            assertEquals(1, executor.pending());
            assertFalse(fixture.listenerOpened.get());
            executor.runNext("test-managed-remote-io");
            fixture.runPending();
            assertEquals(1, executor.pending());
            executor.runNext("test-managed-remote-io");
            fixture.runPending();

            assertEquals("test-managed-remote-io", authenticationThread.get());
            assertTrue(fixture.listenerOpened.get());
            assertTrue(fixture.observedState(ConnectionLifecycleState.ESTABLISHED));
        }
    }

    /**
     * PNFT-05：验证连续丢失确认次数的心跳后关闭连接并记录指标输入事件。
     */
    @Test
    void pnft05MissedHeartbeatsShouldCloseEstablishedConnection() {
        ProductionNetworkConfig config = fastConfig().withHeartbeat(Duration.ofMillis(10), 2);
        try (Fixture fixture = new Fixture(config, acceptingPolicy(), NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            assertTrue(fixture.listenerOpened.get());

            fixture.channel.advanceTimeBy(10, TimeUnit.MILLISECONDS);
            fixture.runPending();
            assertTrue(fixture.channel.isActive());

            fixture.channel.advanceTimeBy(10, TimeUnit.MILLISECONDS);
            fixture.runPending();
            assertFalse(fixture.channel.isActive());
            assertTrue(fixture.observedEvent(ConnectionLifecycleEventType.HEARTBEAT_TIMEOUT));
            assertTrue(fixture.observedError(NetErrorCode.HEARTBEAT_TIMEOUT));
        }
    }

    /** 心跳超时默认关闭；显式识别的客户端保活帧仍交给 policy 消费。 */
    @Test
    void heartbeatTimeoutShouldBeOptIn() {
        AtomicInteger heartbeatChecks = new AtomicInteger();
        ProductionNetworkPolicy policy = new ProductionNetworkPolicy() {
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection, final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            @Override
            public boolean isHeartbeat(final IConnection connection, final ProtocolFrame frame) {
                heartbeatChecks.incrementAndGet();
                return frame.protocolId() == 9;
            }
        };
        try (Fixture fixture = new Fixture(
                ProductionNetworkConfig.defaults("heartbeat-disabled"), policy,
                NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            fixture.channel.advanceTimeBy(60, TimeUnit.SECONDS);
            fixture.runPending();
            assertTrue(fixture.channel.isActive());
            fixture.write(frame(9, 1, "heartbeat"));
            assertTrue(fixture.channel.isActive());
            assertEquals(1, heartbeatChecks.get());
            assertEquals(0, fixture.businessCalls.get());
            assertFalse(fixture.observedEvent(ConnectionLifecycleEventType.HEARTBEAT_TIMEOUT));
        }
    }

    /** 关闭心跳检查仍保留参数，并且其他配置复制方法不意外开启检查。 */
    @Test
    void heartbeatToggleShouldPreserveSettings() {
        var enabled = ProductionNetworkConfig.defaults("toggle").withHeartbeat(Duration.ofSeconds(3), 4);
        assertTrue(enabled.heartbeatEnabled());
        var disabled = enabled.withHeartbeatEnabled(false)
                .withAdmissionTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(2))
                .withMaxInboundFrames(5).withTlsRequired(true);
        assertFalse(disabled.heartbeatEnabled());
        assertEquals(Duration.ofSeconds(3), disabled.heartbeatInterval());
        assertEquals(Duration.ofSeconds(12), disabled.heartbeatTimeout());
        assertTrue(disabled.withHeartbeatEnabled(true).heartbeatEnabled());
    }

    /** 鉴权超时不依赖心跳开关；迟到成功不得重新打开已关闭连接。 */
    @Test
    void authenticationTimeoutMustRemainWhenHeartbeatIsDisabled() {
        var authentication = new CompletableFuture<NetworkAdmissionDecision>();
        var config = ProductionNetworkConfig.defaults("auth-timeout")
                .withAdmissionTimeouts(Duration.ofSeconds(1), Duration.ofMillis(10));
        try (Fixture fixture = new Fixture(config, pendingAuthenticationPolicy(authentication),
                NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            fixture.channel.advanceTimeBy(11, TimeUnit.MILLISECONDS);
            fixture.runPending();
            assertFalse(fixture.channel.isActive());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.AUTHENTICATION_TIMEOUT));
            authentication.complete(NetworkAdmissionDecision.allow());
            fixture.runPending();
            assertFalse(fixture.listenerOpened.get());
            assertEquals(0, fixture.businessCalls.get());
        }
    }

    /** 显式连接限流在握手前生效。 */
    @Test
    void connectionLimiterMustRejectBeforeHandshake() {
        AtomicInteger handshakes = new AtomicInteger();
        ProductionNetworkPolicy policy = (connection, frame) -> {
            handshakes.incrementAndGet();
            return NetworkAdmissionDecision.allow();
        };
        try (Fixture fixture = new Fixture(ProductionNetworkConfig.defaults("connection-limit"),
                policy, connection -> false, Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            assertFalse(fixture.channel.isActive());
            assertEquals(0, handshakes.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.CONNECTION_RATE_LIMITED));
        }
    }

    /** 心跳默认关闭时，显式安全链的业务重放拒绝仍不可绕过。 */
    @Test
    void explicitSecurityChainMustCheckReplayWhenHeartbeatIsDisabled() {
        var now = java.time.Instant.now();
        var identity = new group.zn.zero.security.SecurityContext("alice", now, now.plusSeconds(60),
                "tcp", "peer", "trusted", "trace", "session",
                java.util.Set.of("network.request"), java.util.Map.of());
        var chain = new SecurityChain(request -> CompletableFuture.completedFuture(
                group.zn.zero.security.AuthenticationProvider.AuthenticationResult.accepted(identity)),
                group.zn.zero.security.ReplayProtection.failClosed(), null, false);
        try (Fixture fixture = new Fixture(ProductionNetworkConfig.defaults("replay-disabled-heartbeat"),
                acceptingPolicy(), NetworkRateLimiter.permitAll(), Runnable::run, chain)) {
            fixture.write(frame(1, 1, "handshake"));
            assertTrue(fixture.listenerOpened.get());
            fixture.write(frame(2, 1, "business"));
            assertEquals(0, fixture.businessCalls.get());
            assertTrue(fixture.observedEvent(ConnectionLifecycleEventType.FRAME_REJECTED));
            assertTrue(fixture.observedReason(ConnectionRejectionReason.REPLAY_DETECTED));
        }
    }

    /** 显式安全链必须执行其鉴权 provider，不能回退到 permissive policy。 */
    @Test
    void explicitSecurityChainMustEnforceAuthentication() {
        AtomicInteger policyAuthentications = new AtomicInteger();
        ProductionNetworkPolicy policy = new ProductionNetworkPolicy() {
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection, final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            @Override
            public CompletionStage<NetworkAdmissionDecision> authenticate(
                    final IConnection connection, final ProtocolFrame handshakeFrame) {
                policyAuthentications.incrementAndGet();
                return CompletableFuture.completedFuture(NetworkAdmissionDecision.allow());
            }
        };
        try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(),
                Runnable::run, SecurityChain.failClosed())) {
            fixture.write(frame(1, 1, "handshake"));
            assertFalse(fixture.channel.isActive());
            assertEquals(0, policyAuthentications.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.AUTHENTICATION_REJECTED));
        }
    }

    /**
     * PNFT-06：验证鉴权期间有界队列达到上限后拒绝连接，不会无界积压。
     */
    @Test
    void pnft06AdmissionQueueOverflowShouldRejectConnection() {
        CompletableFuture<NetworkAdmissionDecision> authentication = new CompletableFuture<>();
        ProductionNetworkPolicy policy = pendingAuthenticationPolicy(authentication);
        ProductionNetworkConfig config = fastConfig().withMaxInboundFrames(2).withHeartbeatEnabled(false);
        try (Fixture fixture = new Fixture(config, policy, NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            fixture.write(frame(2, 1, "queued-1"));
            fixture.write(frame(2, 1, "queued-2"));
            fixture.write(frame(2, 1, "overflow"));

            assertFalse(fixture.channel.isActive());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.INBOUND_OVERFLOW));
            assertTrue(fixture.observedError(NetErrorCode.INBOUND_OVERFLOW));
            assertEquals(0, fixture.businessCalls.get());
        }
    }

    /**
     * PNFT-07：验证 frame 限流后不执行业务 handler。
     */
    @Test
    void pnft07RateLimitedFrameShouldNotEnterBusinessHandler() {
        NetworkRateLimiter limiter = new NetworkRateLimiter() {
            /** 允许新连接。 */
            @Override
            public boolean allowConnection(final IConnection connection) {
                return true;
            }

            /** 拒绝业务 frame。 */
            @Override
            public boolean allowAdmissionFrame(final IConnection connection, final ProtocolFrame frame) {
                return true;
            }

            /** 拒绝已建立连接的业务 frame。 */
            @Override
            public boolean allowFrame(final IConnection connection, final ProtocolFrame frame) {
                return false;
            }
        };
        try (Fixture fixture = new Fixture(fastConfig(), acceptingPolicy(), limiter, Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));
            assertTrue(fixture.listenerOpened.get());
            fixture.write(frame(2, 1, "business"));

            assertFalse(fixture.channel.isActive());
            assertEquals(0, fixture.businessCalls.get());
            assertTrue(fixture.observedReason(ConnectionRejectionReason.FRAME_RATE_LIMITED));
            assertTrue(fixture.observedError(NetErrorCode.RATE_LIMITED));
        }
    }

    /**
     * PNFT-08：验证有主体的连接必须等待重连协调端口完成后才能 ESTABLISHED。
     */
    @Test
    void pnft08ReconnectShouldCompleteThroughCoordinatorBeforeOpen() {
        CompletableFuture<Void> reconnect = new CompletableFuture<>();
        AtomicBoolean reconnectCalled = new AtomicBoolean();
        ProductionNetworkPolicy policy = reconnectPolicy(reconnect, reconnectCalled);
        try (Fixture fixture = new Fixture(fastConfig(), policy, NetworkRateLimiter.permitAll(), Runnable::run)) {
            fixture.write(frame(1, 1, "handshake"));

            assertTrue(reconnectCalled.get());
            assertFalse(fixture.listenerOpened.get());
            reconnect.complete(null);
            fixture.runPending();

            assertTrue(fixture.listenerOpened.get());
            IConnection connection = fixture.connection.get();
            assertNotNull(connection);
            assertEquals("player-1001", connection.attributes()
                    .get(ProductionNetworkConnectionAttributes.SUBJECT_ID)
                    .orElseThrow());
            assertTrue(fixture.observedEvent(ConnectionLifecycleEventType.RECONNECT_COMPLETED));
            assertTrue(fixture.observedState(ConnectionLifecycleState.ESTABLISHED));
        }
    }

    /**
     * PNFT-10：验证未传入 production lifecycle 时仍保持 local TCP 的立即打开与直接分发语义。
     */
    @Test
    void pnft10LocalTcpShouldRemainImmediatelyAvailable() {
        AtomicBoolean opened = new AtomicBoolean();
        AtomicInteger handled = new AtomicInteger();
        ServerFrameHandler handler = (connection, request) -> {
            handled.incrementAndGet();
            return CompletableFuture.completedFuture(List.of());
        };
        ConnectionListener listener = new ConnectionListener() {
            /** 记录 local 连接立即打开。 */
            @Override
            public void onOpen(final IConnection connection) {
                opened.set(true);
            }
        };

        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyFrameChannelHandler(handler, listener, Runnable::run));
        try {
            assertTrue(opened.get());
            channel.writeInbound(frame(2, 1, "local-business"));
            channel.runPendingTasks();

            assertEquals(1, handled.get());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * 验证 observer 下游失败只会通过连接 EventLoop 通知业务监听器。
     *
     * @throws InterruptedException 等待测试 observer 线程被中断时抛出。
     */
    @Test
    void observerFailureShouldReturnToConnectionEventLoop() throws InterruptedException {
        QueueExecutor observerExecutor = new QueueExecutor();
        AtomicReference<String> exceptionThread = new AtomicReference<>();
        ServerFrameHandler handler = (connection, request) -> CompletableFuture.completedFuture(List.of());
        ConnectionListener listener = new ConnectionListener() {
            /** 记录异常通知线程。 */
            @Override
            public void onException(final IConnection connection, final Throwable cause) {
                exceptionThread.set(Thread.currentThread().getName());
            }
        };
        ProductionNetworkLifecycle lifecycle = new ProductionNetworkLifecycle(
                fastConfig(),
                acceptingPolicy(),
                NetworkRateLimiter.permitAll(),
                observation -> {
                    throw new IllegalStateException("observer failed");
                },
                Runnable::run,
                observerExecutor);

        EmbeddedChannel channel = new EmbeddedChannel(
                new NettyFrameChannelHandler(handler, listener, Runnable::run, lifecycle));
        try {
            observerExecutor.runNext("test-observer-executor");
            assertNull(exceptionThread.get());

            channel.runPendingTasks();
            assertEquals(Thread.currentThread().getName(), exceptionThread.get());
            assertTrue(channel.isActive());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * 验证共享执行器即使延迟执行，也只收到一个单连接 drain，accept 到 close 的事件保持因果顺序。
     *
     * @throws InterruptedException 等待测试 observer 线程被中断时抛出。
     */
    @Test
    void observerEventsShouldRemainOrderedPerConnection() throws InterruptedException {
        QueueExecutor observerExecutor = new QueueExecutor();
        List<ConnectionLifecycleEventType> events = new CopyOnWriteArrayList<>();
        ProductionNetworkLifecycle lifecycle = new ProductionNetworkLifecycle(
                fastConfig(),
                acceptingPolicy(),
                NetworkRateLimiter.permitAll(),
                observation -> events.add(observation.event()),
                Runnable::run,
                observerExecutor);
        ServerFrameHandler handler = (connection, request) -> CompletableFuture.completedFuture(List.of());
        EmbeddedChannel channel = new EmbeddedChannel(new NettyFrameChannelHandler(
                handler,
                new ConnectionListener() { },
                Runnable::run,
                lifecycle));
        try {
            assertEquals(1, observerExecutor.pending());
            channel.close();
            channel.runPendingTasks();
            assertEquals(1, observerExecutor.pending());

            observerExecutor.runNext("ordered-observer-executor");

            assertEquals(List.of(
                    ConnectionLifecycleEventType.CHANNEL_ACCEPTED,
                    ConnectionLifecycleEventType.STATE_TRANSITION,
                    ConnectionLifecycleEventType.CHANNEL_CLOSED), events);
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    /** 默认 no-op observer 不应创建观测任务或依赖 observer executor。 */
    @Test
    void noOpObserverShouldAvoidExecutorSubmission() {
        AtomicInteger submissions = new AtomicInteger();
        AtomicInteger businessCalls = new AtomicInteger();
        Executor rejectingExecutor = command -> submissions.incrementAndGet();
        ProductionNetworkLifecycle lifecycle = new ProductionNetworkLifecycle(
                fastConfig(),
                acceptingPolicy(),
                NetworkRateLimiter.permitAll(),
                ConnectionLifecycleObserver.noOp(),
                Runnable::run,
                rejectingExecutor);
        EmbeddedChannel channel = new EmbeddedChannel(new NettyFrameChannelHandler(
                (connection, request) -> {
                    businessCalls.incrementAndGet();
                    return CompletableFuture.completedFuture(List.of());
                },
                new ConnectionListener() { },
                Runnable::run,
                lifecycle));
        try {
            channel.writeInbound(frame(1, 1, "handshake"));
            channel.runPendingTasks();
            channel.writeInbound(frame(2, 1, "business"));
            channel.runPendingTasks();
            assertEquals(1, businessCalls.get());
            assertTrue(channel.isActive());
            channel.close();
            channel.runPendingTasks();
            assertEquals(0, submissions.get());
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static ProductionNetworkConfig fastConfig() {
        return ProductionNetworkConfig.defaults("focused-test")
                .withAdmissionTimeouts(Duration.ofSeconds(1), Duration.ofSeconds(1))
                .withHeartbeat(Duration.ofSeconds(1), 2)
                .withMaxInboundFrames(8);
    }

    private static ProductionNetworkPolicy acceptingPolicy() {
        return (connection, frame) -> NetworkAdmissionDecision.allow();
    }

    private static ProductionNetworkPolicy rejectingAuthenticationPolicy() {
        return new ProductionNetworkPolicy() {
            /** 校验握手。 */
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            /** 拒绝鉴权。 */
            @Override
            public CompletionStage<NetworkAdmissionDecision> authenticate(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return CompletableFuture.completedFuture(NetworkAdmissionDecision.rejected(
                        NetErrorCode.AUTHENTICATION_REJECTED,
                        ConnectionRejectionReason.AUTHENTICATION_REJECTED));
            }
        };
    }

    private static ProductionNetworkPolicy pendingAuthenticationPolicy(
            final CompletionStage<NetworkAdmissionDecision> authentication) {
        return new ProductionNetworkPolicy() {
            /** 校验握手。 */
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            /** 返回可控鉴权 stage。 */
            @Override
            public CompletionStage<NetworkAdmissionDecision> authenticate(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return authentication;
            }
        };
    }

    private static ProductionNetworkPolicy reconnectPolicy(
            final CompletionStage<Void> reconnect,
            final AtomicBoolean reconnectCalled) {
        return new ProductionNetworkPolicy() {
            /** 校验握手。 */
            @Override
            public NetworkAdmissionDecision validateHandshake(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return NetworkAdmissionDecision.allow();
            }

            /** 返回带玩家主体的鉴权成功结果。 */
            @Override
            public CompletionStage<NetworkAdmissionDecision> authenticate(
                    final IConnection connection,
                    final ProtocolFrame handshakeFrame) {
                return CompletableFuture.completedFuture(NetworkAdmissionDecision.authenticated("player-1001"));
            }

            /** 通过测试 spy 表示 player actor 消息端口。 */
            @Override
            public CompletionStage<Void> coordinateReconnect(
                    final IConnection connection,
                    final String subjectId,
                    final Duration reconnectWindow) {
                reconnectCalled.set(true);
                return reconnect;
            }
        };
    }

    private static ProtocolFrame frame(final int protocolId, final int version, final String payload) {
        return new ProtocolFrame(
                protocolId,
                version,
                0,
                null,
                payload.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * 单连接 EmbeddedChannel 测试夹具。
     */
    private static final class Fixture implements AutoCloseable {

        /** 生命周期观测事件。 */
        private final List<ConnectionLifecycleObservation> observations =
                new java.util.concurrent.CopyOnWriteArrayList<>();
        /** 业务调用次数。 */
        private final AtomicInteger businessCalls = new AtomicInteger();
        /** listener 是否打开。 */
        private final AtomicBoolean listenerOpened = new AtomicBoolean();
        /** 已建立连接。 */
        private final AtomicReference<IConnection> connection = new AtomicReference<>();
        /** EmbeddedChannel。 */
        private final EmbeddedChannel channel;

        Fixture(
                final ProductionNetworkConfig config,
                final ProductionNetworkPolicy policy,
                final NetworkRateLimiter limiter,
                final Executor authenticationExecutor) {
            this(config, policy, limiter, authenticationExecutor, null);
        }

        Fixture(
                final ProductionNetworkConfig config,
                final ProductionNetworkPolicy policy,
                final NetworkRateLimiter limiter,
                final Executor authenticationExecutor,
                final SecurityChain securityChain) {
            ServerFrameHandler handler = (currentConnection, frame) -> {
                businessCalls.incrementAndGet();
                return CompletableFuture.completedFuture(List.of());
            };
            ConnectionListener listener = new ConnectionListener() {
                /** 记录 ESTABLISHED 后的连接。 */
                @Override
                public void onOpen(final IConnection currentConnection) {
                    connection.set(currentConnection);
                    listenerOpened.set(true);
                }
            };
            ProductionNetworkLifecycle lifecycle = securityChain == null
                    ? new ProductionNetworkLifecycle(config, policy, limiter, observations::add,
                            authenticationExecutor, Runnable::run)
                    : new ProductionNetworkLifecycle(config, policy, securityChain, limiter, observations::add,
                            authenticationExecutor, Runnable::run);
            channel = new EmbeddedChannel(new NettyFrameChannelHandler(handler, listener, Runnable::run, lifecycle));
            runPending();
        }

        private void write(final ProtocolFrame frame) {
            if (channel.isActive()) {
                channel.writeInbound(frame);
                runPending();
            }
        }

        private void runPending() {
            channel.runPendingTasks();
            channel.runScheduledPendingTasks();
            channel.runPendingTasks();
        }

        private boolean observedReason(final ConnectionRejectionReason reason) {
            return observations.stream().anyMatch(observation -> observation.reason() == reason);
        }

        private boolean observedError(final NetErrorCode errorCode) {
            return observations.stream().anyMatch(observation -> observation.errorCode() == errorCode);
        }

        private boolean observedState(final ConnectionLifecycleState state) {
            return observations.stream().anyMatch(observation -> observation.state() == state);
        }

        private boolean observedEvent(final ConnectionLifecycleEventType event) {
            return observations.stream().anyMatch(observation -> observation.event() == event);
        }

        /**
         * 释放 EmbeddedChannel 资源。
         */
        @Override
        public void close() {
            channel.finishAndReleaseAll();
        }
    }

    /**
     * 可显式在命名线程中执行任务的受管执行器测试替身。
     */
    private static final class QueueExecutor implements Executor {

        /** 待执行任务。 */
        private final Queue<Runnable> tasks = new ConcurrentLinkedQueue<>();

        /**
         * 排队任务。
         *
         * @param command 任务；不可为空。
         */
        @Override
        public void execute(final Runnable command) {
            tasks.add(command);
        }

        private int pending() {
            return tasks.size();
        }

        private void runNext(final String threadName) throws InterruptedException {
            Runnable task = tasks.remove();
            Thread thread = new Thread(task, threadName);
            thread.start();
            thread.join(Duration.ofSeconds(3).toMillis());
            assertFalse(thread.isAlive());
        }
    }
}
