package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;

/**
 * Kafka RPC 构造与关闭资源安全测试。
 *
 * @author zn
 */
class KafkaRpcResourceSafetyTest {

    /** 测试中的敏感 broker 标记。 */
    private static final String BOOTSTRAP_SECRET = "broker-secret.example:19092";

    /** 测试中的敏感 producer 属性值。 */
    private static final String PRODUCER_SECRET = "producer-secret-value";

    /** 测试中的敏感 consumer 属性值。 */
    private static final String CONSUMER_SECRET = "consumer-secret-value";

    /** 测试中的敏感第三方异常消息。 */
    private static final String FAILURE_SECRET = "third-party-secret-message";

    /**
     * 验证 settings 的字符串表示不泄露任何字符串、数值、时长或扩展属性配置原值。
     */
    @Test
    void settingsToStringShouldRedactSecretBearingValues() {
        int pendingCapacitySentinel = 731_927;
        Duration pollTimeoutSentinel = Duration.ofNanos(918_273_645L);
        Duration closeTimeoutSentinel = Duration.ofNanos(564_738_291L);
        KafkaRpcSettings settings = new KafkaRpcSettings(
                BOOTSTRAP_SECRET,
                "client-secret",
                "consumer-group-secret",
                "topic-prefix-secret",
                "reply-secret",
                pendingCapacitySentinel,
                pollTimeoutSentinel,
                closeTimeoutSentinel,
                Map.of("sasl.jaas.config", PRODUCER_SECRET),
                Map.of("ssl.truststore.password", CONSUMER_SECRET));

        String diagnostic = settings.toString();

        assertFalse(diagnostic.contains(BOOTSTRAP_SECRET));
        assertFalse(diagnostic.contains(PRODUCER_SECRET));
        assertFalse(diagnostic.contains(CONSUMER_SECRET));
        assertFalse(diagnostic.contains("client-secret"));
        assertFalse(diagnostic.contains("reply-secret"));
        assertFalse(diagnostic.contains(String.valueOf(pendingCapacitySentinel)));
        assertFalse(diagnostic.contains(pollTimeoutSentinel.toString()));
        assertFalse(diagnostic.contains(closeTimeoutSentinel.toString()));
        assertFalse(diagnostic.contains("pendingCapacity="));
        assertFalse(diagnostic.contains("pollTimeout=PT"));
        assertFalse(diagnostic.contains("closeTimeout=PT"));
        assertTrue(diagnostic.contains("pendingCapacityConfigured=true"));
        assertTrue(diagnostic.contains("pollTimeoutConfigured=true"));
        assertTrue(diagnostic.contains("closeTimeoutConfigured=true"));
        assertTrue(diagnostic.contains("producerPropertyCount=1"));
        assertTrue(diagnostic.contains("consumerPropertyCount=1"));
    }

    /**
     * 验证 Adapter 初始 reply 订阅失败会逆序回滚订阅、时间轮和网关。
     */
    @Test
    void adapterConstructionFailureShouldRollbackAndSanitizeFailureGraph() {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        ConstructionFailingGateway gateway = new ConstructionFailingGateway();

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class,
                () -> new KafkaRpcAdapter(settings(Duration.ofMillis(100), Map.of(), Map.of()), gateway));

        assertEquals("subscribe kafka rpc reply topic failed", failure.getMessage());
        assertEquals(1, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(List.of("subscribe", "unsubscribe", "close"), gateway.events());
        assertEquals(2, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        assertEquals(timeoutWorkersBefore, aliveTimeoutWorkers());
    }

    /**
     * 验证 Adapter 构造期间遇到 {@link Error} 仍会关闭时间轮与网关，并安全聚合全部回滚失败。
     */
    @Test
    void adapterConstructionErrorShouldRollbackAndSanitizeFailureGraph() {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        ConstructionFailingGateway gateway = new ConstructionFailingGateway(true);

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class,
                () -> new KafkaRpcAdapter(settings(Duration.ofMillis(100), Map.of(), Map.of()), gateway));

        assertEquals("subscribe kafka rpc reply topic failed", failure.getMessage());
        assertEquals(1, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(List.of("subscribe", "unsubscribe", "close"), gateway.events());
        assertEquals(2, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        assertEquals(timeoutWorkersBefore, aliveTimeoutWorkers());
    }

    /**
     * 验证 Adapter close 会继续清理全部订阅与网关，并且重复 close 不重复执行。
     */
    @Test
    void adapterCloseShouldAggregateFailuresAndRemainIdempotent() {
        CloseFailingGateway gateway = new CloseFailingGateway();
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(
                settings(Duration.ofMillis(100), Map.of(), Map.of()),
                gateway);
        adapter.register("service-one", "method", "topic-one", "group-one",
                request -> CompletableFuture.completedFuture(null));
        adapter.register("service-two", "method", "topic-two", "group-two",
                request -> CompletableFuture.completedFuture(null));
        gateway.failCloseOperations();

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, adapter::close);

        assertEquals(3, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(3, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        adapter.close();
        assertEquals(3, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
    }

    /**
     * 验证 Adapter close 遇到多个 {@link Error} 时仍继续关闭全部资源，并保持重复 close 幂等。
     */
    @Test
    void adapterCloseShouldAggregateErrorsAndRemainIdempotent() {
        CloseFailingGateway gateway = new CloseFailingGateway(true);
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(
                settings(Duration.ofMillis(100), Map.of(), Map.of()),
                gateway);
        adapter.register("service-one", "method", "topic-one", "group-one",
                request -> CompletableFuture.completedFuture(null));
        adapter.register("service-two", "method", "topic-two", "group-two",
                request -> CompletableFuture.completedFuture(null));
        gateway.failCloseOperations();

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, adapter::close);

        assertEquals(3, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(3, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        adapter.close();
        assertEquals(3, gateway.unsubscribeCount());
        assertEquals(1, gateway.closeCount());
    }

    /**
     * 验证 request 订阅失败会完整回滚路由，且同一路由重试会重新执行真实订阅。
     *
     * @throws InterruptedException 等待 pending 时间轮线程退出时被中断。
     */
    @Test
    void requestSubscriptionFailureShouldRollbackRouteAndRemainRetryable() throws InterruptedException {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        ReplayFailingGateway gateway = new ReplayFailingGateway(1, 1);
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(
                settings(Duration.ofMillis(100), Map.of(), Map.of()),
                gateway);

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, () ->
                adapter.register("retry-service", "retry-method", "retry-topic", "retry-group",
                        request -> CompletableFuture.completedFuture(null)));

        assertEquals("subscribe kafka rpc request topic failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        assertEquals(1, gateway.requestSubscribeCount());
        assertEquals(1, gateway.requestUnsubscribeCount());

        adapter.register("retry-service", "retry-method", "retry-topic", "retry-group",
                request -> CompletableFuture.completedFuture(null));
        assertEquals(2, gateway.requestSubscribeCount());

        adapter.close();

        assertEquals(2, gateway.requestUnsubscribeCount());
        assertEquals(1, gateway.replyUnsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(List.of(
                "reply-subscribe",
                "request-subscribe-1",
                "request-unsubscribe-1",
                "request-subscribe-2",
                "request-unsubscribe-2",
                "reply-unsubscribe",
                "gateway-close"), gateway.events());
        adapter.close();
        assertEquals(1, gateway.closeCount());
        assertEventually(() -> aliveTimeoutWorkers() == timeoutWorkersBefore, Duration.ofSeconds(2));
    }

    /**
     * 验证 handler replay 中途订阅失败后，已成功注册的订阅仍可由 adapter 回滚关闭。
     *
     * @throws InterruptedException 等待 pending 时间轮线程退出时被中断。
     */
    @Test
    void handlerReplayFailureShouldRollbackPreviouslyRegisteredSubscriptions() throws InterruptedException {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        ReplayFailingGateway gateway = new ReplayFailingGateway(2, 1);
        KafkaRpcAdapter adapter = new KafkaRpcAdapter(
                settings(Duration.ofMillis(100), Map.of(), Map.of()),
                gateway);
        adapter.register("first-service", "method", "first-topic", "first-group",
                request -> CompletableFuture.completedFuture(null));

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, () ->
                adapter.register("second-service", "method", "second-topic", "second-group",
                        request -> CompletableFuture.completedFuture(null)));

        assertEquals("subscribe kafka rpc request topic failed", failure.getMessage());
        assertEquals(1, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        assertEquals(2, gateway.requestSubscribeCount());
        assertEquals(1, gateway.requestUnsubscribeCount());

        adapter.close();

        assertEquals(2, gateway.requestUnsubscribeCount());
        assertEquals(1, gateway.replyUnsubscribeCount());
        assertEquals(1, gateway.closeCount());
        assertEquals(List.of(
                "reply-subscribe",
                "request-subscribe-1",
                "request-subscribe-2",
                "request-unsubscribe-1",
                "request-unsubscribe-2",
                "reply-unsubscribe",
                "gateway-close"), gateway.events());
        adapter.close();
        assertEquals(1, gateway.closeCount());
        assertEventually(() -> aliveTimeoutWorkers() == timeoutWorkersBefore, Duration.ofSeconds(2));
    }

    /**
     * 验证 pending close 的 observer {@link Error} 不会阻断 future 失败与时间轮线程关闭。
     *
     * @throws InterruptedException 等待时间轮线程退出时被中断。
     */
    @Test
    void pendingCloseErrorShouldStillCloseTimeoutWheel() throws InterruptedException {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        AtomicReference<CompletableFuture<RpcResponse>> responseReference = new AtomicReference<>();
        AtomicBoolean terminalBeforeObserver = new AtomicBoolean();
        RpcTransportObserver observer = event -> {
            if (event.type() == RpcTransportEventType.PENDING_FAILED) {
                terminalBeforeObserver.set(responseReference.get().isDone());
                throw new AssertionError(FAILURE_SECRET + "-pending-observer");
            }
        };
        KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(1, observer, "kafka-test");
        CompletableFuture<RpcResponse> response = pending.register(request("pending-error"));
        responseReference.set(response);

        pending.close();

        assertTrue(response.isCompletedExceptionally());
        assertTrue(terminalBeforeObserver.get());
        assertEventually(() -> aliveTimeoutWorkers() == timeoutWorkersBefore, Duration.ofSeconds(2));
        pending.close();
    }

    /**
     * 验证 complete、fail、close 与 rejected 通知遇到 observer {@link Error} 时，future 已先进入终态。
     */
    @Test
    void pendingTerminalObserverErrorsShouldNotChangeFutureOutcomes() {
        ConcurrentMap<String, CompletableFuture<RpcResponse>> futures = new ConcurrentHashMap<>();
        AtomicBoolean terminalBeforeObserver = new AtomicBoolean(true);
        AtomicInteger terminalNotifications = new AtomicInteger();
        RpcTransportObserver observer = event -> {
            if (terminalEvent(event.type())) {
                CompletableFuture<RpcResponse> future = futures.get(event.correlationId());
                if (future != null && !future.isDone()) {
                    terminalBeforeObserver.set(false);
                }
                if (future == null && !"rejected-error".equals(event.correlationId())) {
                    terminalBeforeObserver.set(false);
                }
                terminalNotifications.incrementAndGet();
                throw new AssertionError(FAILURE_SECRET + "-terminal-observer");
            }
        };
        KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(4, observer, "kafka-test");
        CompletableFuture<RpcResponse> completed = pending.register(request("complete-error"));
        CompletableFuture<RpcResponse> failed = pending.register(request("fail-error"));
        CompletableFuture<RpcResponse> closed = pending.register(request("close-error"));
        futures.put("complete-error", completed);
        futures.put("fail-error", failed);
        futures.put("close-error", closed);
        RpcResponse response = new RpcResponse(
                "complete-error",
                "trace",
                SystemErrorCode.OK,
                new byte[0]);

        assertTrue(pending.complete(response));
        assertTrue(pending.fail(
                "fail-error",
                group.zn.zero.rpc.error.RpcErrorCode.TRANSPORT_UNAVAILABLE,
                "test failure",
                null));
        pending.close();
        CompletableFuture<RpcResponse> rejected = pending.register(request("rejected-error"));

        assertEquals(response, completed.join());
        assertTrue(failed.isCompletedExceptionally());
        assertTrue(closed.isCompletedExceptionally());
        assertTrue(rejected.isCompletedExceptionally());
        assertTrue(terminalBeforeObserver.get());
        assertEquals(4, terminalNotifications.get());
    }

    /**
     * 验证 timeout observer 连续抛出 {@link Error} 不会杀死时间轮，且每个 future 均先进入超时终态。
     *
     * @throws InterruptedException 等待两个超时任务执行时被中断。
     */
    @Test
    void timeoutObserverErrorsShouldNotKillWheelOrDelayFutureTerminalState() throws InterruptedException {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        ConcurrentMap<String, CompletableFuture<RpcResponse>> futures = new ConcurrentHashMap<>();
        AtomicBoolean terminalBeforeObserver = new AtomicBoolean(true);
        AtomicInteger timeoutNotifications = new AtomicInteger();
        RpcTransportObserver observer = event -> {
            if (event.type() == RpcTransportEventType.PENDING_TIMED_OUT) {
                CompletableFuture<RpcResponse> future = futures.get(event.correlationId());
                if (future == null || !future.isDone()) {
                    terminalBeforeObserver.set(false);
                }
                timeoutNotifications.incrementAndGet();
                throw new AssertionError(FAILURE_SECRET + "-timeout-observer");
            }
        };
        KafkaRpcPendingRequests pending = new KafkaRpcPendingRequests(
                2,
                Duration.ofMillis(5),
                8,
                observer,
                "kafka-test");
        CompletableFuture<RpcResponse> first = pending.register(request("timeout-one", 150L));
        CompletableFuture<RpcResponse> second = pending.register(request("timeout-two", 200L));
        futures.put("timeout-one", first);
        futures.put("timeout-two", second);

        assertEventually(() -> first.isDone() && second.isDone(), Duration.ofSeconds(2));
        assertEventually(() -> timeoutNotifications.get() == 2, Duration.ofSeconds(2));

        assertTrue(first.isCompletedExceptionally());
        assertTrue(second.isCompletedExceptionally());
        assertTrue(terminalBeforeObserver.get());
        assertEquals(2, timeoutNotifications.get());
        pending.close();
        assertEventually(() -> aliveTimeoutWorkers() == timeoutWorkersBefore, Duration.ofSeconds(2));
    }

    /**
     * 验证 timeout handler 自身连续抛出 {@link Error} 时，时间轮 worker 仍会处理后续任务。
     *
     * @throws InterruptedException 等待时间轮任务执行时被中断。
     */
    @Test
    void timeoutHandlerErrorsShouldNotKillWheelWorker() throws InterruptedException {
        int timeoutWorkersBefore = aliveTimeoutWorkers();
        AtomicInteger callbacks = new AtomicInteger();
        KafkaRpcTimeoutWheel wheel = new KafkaRpcTimeoutWheel(
                Duration.ofMillis(5),
                8,
                (correlationId, token) -> {
                    callbacks.incrementAndGet();
                    throw new AssertionError(FAILURE_SECRET + "-timeout-handler");
                });
        wheel.schedule("timeout-handler-one", Instant.now().plusMillis(100L), 1L);
        wheel.schedule("timeout-handler-two", Instant.now().plusMillis(150L), 2L);

        assertEventually(() -> callbacks.get() == 2, Duration.ofSeconds(2));

        wheel.close();
        assertEventually(() -> aliveTimeoutWorkers() == timeoutWorkersBefore, Duration.ofSeconds(2));
    }

    /**
     * 验证 producer 创建失败不会把第三方异常消息挂到公开异常图。
     */
    @Test
    void producerConstructionFailureShouldExposeOnlySafeFrameworkFailure() {
        KafkaRpcClientFactory factory = new KafkaRpcClientFactory() {
            /**
             * 模拟 producer 创建失败。
             *
             * @param properties producer 配置；不可为空。
             * @return 不会正常返回。
             * @throws KafkaException 始终抛出带敏感标记的测试异常。
             */
            @Override
            public Producer<String, byte[]> createProducer(final Properties properties) {
                throw new KafkaException(FAILURE_SECRET);
            }

            /**
             * 拒绝意外的 consumer 创建。
             *
             * @param properties consumer 配置；不可为空。
             * @return 不会正常返回。
             * @throws AssertionError 始终抛出，表示测试路径错误。
             */
            @Override
            public Consumer<String, byte[]> createConsumer(final Properties properties) {
                throw new AssertionError("consumer must not be created");
            }
        };

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, () ->
                new ApacheKafkaRpcMessageGateway(
                        settings(Duration.ofMillis(100), Map.of(), Map.of()),
                        RpcTransportObserver.noop(),
                        factory));

        assertEquals("create kafka rpc producer failed", failure.getMessage());
        assertNull(failure.getCause());
        assertSecretAbsent(failure);
    }

    /**
     * 验证 producer 构造抛出 {@link Error} 时仍只公开固定安全异常图。
     */
    @Test
    void producerConstructionErrorShouldExposeOnlySafeFrameworkFailure() {
        KafkaRpcClientFactory factory = new KafkaRpcClientFactory() {
            /**
             * 模拟 producer 构造 Error。
             *
             * @param properties producer 配置；不可为空。
             * @return 不会正常返回。
             * @throws AssertionError 始终抛出带敏感标记的测试错误。
             */
            @Override
            public Producer<String, byte[]> createProducer(final Properties properties) {
                throw new AssertionError(FAILURE_SECRET + "-producer-create");
            }

            /**
             * 拒绝意外的 consumer 创建。
             *
             * @param properties consumer 配置；不可为空。
             * @return 不会正常返回。
             * @throws AssertionError 始终抛出，表示测试路径错误。
             */
            @Override
            public Consumer<String, byte[]> createConsumer(final Properties properties) {
                throw new AssertionError("consumer must not be created");
            }
        };

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, () ->
                new ApacheKafkaRpcMessageGateway(
                        settings(Duration.ofMillis(100), Map.of(), Map.of()),
                        RpcTransportObserver.noop(),
                        factory));

        assertEquals("create kafka rpc producer failed", failure.getMessage());
        assertNull(failure.getCause());
        assertSecretAbsent(failure);
    }

    /**
     * 验证 subscribe 已通过关闭检查后，close 必须等待 worker 发布并将其纳入同一次关闭快照。
     *
     * @throws InterruptedException 等待并发测试线程与探针信号时被中断。
     */
    @Test
    void gatewaySubscribeAndCloseShouldLinearizeWorkerPublication() throws InterruptedException {
        CountDownLatch beforePublication = new CountDownLatch(1);
        CountDownLatch allowPublication = new CountDownLatch(1);
        CountDownLatch closeAttempted = new CountDownLatch(1);
        CountDownLatch closeSnapshot = new CountDownLatch(1);
        AtomicInteger snapshotWorkerCount = new AtomicInteger(-1);
        ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe probe =
                new ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe() {
                    /** 在生命周期锁内暂停 worker 发布，建立确定性的 close 竞争窗口。 */
                    @Override
                    public void beforeWorkerPublication() {
                        beforePublication.countDown();
                        awaitLatchUnchecked(allowPublication);
                    }

                    /** 该测试不暂停退休 worker。 */
                    @Override
                    public void beforeRetiredWorkerStop() {
                    }

                    /**
                     * 记录 close 快照中的 worker 数量。
                     *
                     * @param workerCount 本次 close 快照内的 worker 数量。
                     */
                    @Override
                    public void afterCloseSnapshot(final int workerCount) {
                        snapshotWorkerCount.set(workerCount);
                        closeSnapshot.countDown();
                    }
                };
        MockProducer<String, byte[]> producer = new MockProducer<>();
        List<MockConsumer<String, byte[]>> consumers = new CopyOnWriteArrayList<>();
        TestClientFactory factory = new TestClientFactory(producer, () -> {
            MockConsumer<String, byte[]> consumer = new MockConsumer<>(OffsetResetStrategy.LATEST);
            consumers.add(consumer);
            return consumer;
        });
        ApacheKafkaRpcMessageGateway gateway = new ApacheKafkaRpcMessageGateway(
                settings(Duration.ofSeconds(1), Map.of(), Map.of()),
                RpcTransportObserver.noop(),
                factory,
                probe);
        AtomicReference<Throwable> subscribeFailure = new AtomicReference<>();
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread subscriber = Thread.ofVirtual().name("kafka-resource-safety-subscriber").start(() -> {
            try {
                gateway.subscribe("linearized-topic", "linearized-group", message -> { });
            } catch (RuntimeException | Error failure) {
                subscribeFailure.set(failure);
            }
        });

        assertTrue(beforePublication.await(2, TimeUnit.SECONDS));
        Thread closer = Thread.ofVirtual().name("kafka-resource-safety-closer").start(() -> {
            closeAttempted.countDown();
            try {
                gateway.close();
            } catch (RuntimeException | Error failure) {
                closeFailure.set(failure);
            }
        });
        assertTrue(closeAttempted.await(2, TimeUnit.SECONDS));
        try {
            assertFalse(closeSnapshot.await(100, TimeUnit.MILLISECONDS));
        } finally {
            allowPublication.countDown();
        }

        subscriber.join(2_000L);
        closer.join(2_000L);

        assertFalse(subscriber.isAlive());
        assertFalse(closer.isAlive());
        assertNull(subscribeFailure.get());
        assertNull(closeFailure.get());
        assertTrue(closeSnapshot.await(100, TimeUnit.MILLISECONDS));
        assertEquals(1, snapshotWorkerCount.get());
        assertTrue(producer.closed());
        assertTrue(consumers.stream().allMatch(MockConsumer::closed));
        gateway.close();
        assertEquals(1, snapshotWorkerCount.get());
    }

    /**
     * 验证多个阻塞订阅共享一个绝对关闭截止时间，且 producer 仍会执行关闭。
     */
    @Test
    void gatewayCloseShouldShareOneDeadlineAcrossSubscriptions() throws InterruptedException {
        Duration closeTimeout = Duration.ofMillis(300);
        CountDownLatch pollsStarted = new CountDownLatch(3);
        CountDownLatch releasePolls = new CountDownLatch(1);
        List<BlockingConsumer> consumers = new CopyOnWriteArrayList<>();
        MockProducer<String, byte[]> producer = new MockProducer<>();
        TestClientFactory factory = new TestClientFactory(producer, () -> {
            BlockingConsumer consumer = new BlockingConsumer(pollsStarted, releasePolls);
            consumers.add(consumer);
            return consumer;
        });
        ApacheKafkaRpcMessageGateway gateway = gateway(closeTimeout, factory);
        gateway.subscribe("topic-one", "group-one", message -> { });
        gateway.subscribe("topic-two", "group-two", message -> { });
        gateway.subscribe("topic-three", "group-three", message -> { });
        assertTrue(pollsStarted.await(2, TimeUnit.SECONDS));

        long startedAt = System.nanoTime();
        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, gateway::close);
        long elapsedMillis = Duration.ofNanos(System.nanoTime() - startedAt).toMillis();

        assertTrue(elapsedMillis < 650L, "close elapsed=" + elapsedMillis + "ms");
        assertTrue(producer.closed());
        assertEquals("kafka rpc consumer close deadline exceeded", failure.getMessage());
        assertSecretAbsent(failure);
        releasePolls.countDown();
        assertEventually(() -> consumers.stream().allMatch(BlockingConsumer::closed), Duration.ofSeconds(2));
        gateway.close();
    }

    /**
     * 验证 consumer 与 producer 的多个关闭失败均被安全聚合且全部资源都被尝试。
     */
    @Test
    void gatewayCloseShouldAggregateConsumerAndProducerFailures() throws InterruptedException {
        FailingCloseProducer producer = new FailingCloseProducer();
        List<FailingCloseConsumer> consumers = new CopyOnWriteArrayList<>();
        TestClientFactory factory = new TestClientFactory(producer, () -> {
            FailingCloseConsumer consumer = new FailingCloseConsumer();
            consumers.add(consumer);
            return consumer;
        });
        ApacheKafkaRpcMessageGateway gateway = gateway(Duration.ofSeconds(1), factory);
        gateway.subscribe("topic-one", "group-one", message -> { });
        gateway.subscribe("topic-two", "group-two", message -> { });
        assertEventually(
                () -> consumers.size() == 2 && consumers.stream().allMatch(FailingCloseConsumer::pollStarted),
                Duration.ofSeconds(2));

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, gateway::close);

        assertEquals(1, producer.closeCount());
        assertTrue(consumers.stream().allMatch(consumer -> consumer.closeCount() == 1));
        assertEquals(2, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        gateway.close();
        assertEquals(1, producer.closeCount());
    }

    /**
     * 验证 consumer 与 producer 抛出 {@link Error} 时仍共享关闭 deadline、继续关闭并安全聚合。
     *
     * @throws InterruptedException 等待 consumer worker 启动时被中断。
     */
    @Test
    void gatewayCloseShouldAggregateConsumerAndProducerErrors() throws InterruptedException {
        FailingCloseProducer producer = new FailingCloseProducer(true);
        List<FailingCloseConsumer> consumers = new CopyOnWriteArrayList<>();
        TestClientFactory factory = new TestClientFactory(producer, () -> {
            FailingCloseConsumer consumer = new FailingCloseConsumer(true);
            consumers.add(consumer);
            return consumer;
        });
        ApacheKafkaRpcMessageGateway gateway = gateway(Duration.ofSeconds(1), factory);
        gateway.subscribe("topic-one", "group-one", message -> { });
        gateway.subscribe("topic-two", "group-two", message -> { });
        assertEventually(
                () -> consumers.size() == 2 && consumers.stream().allMatch(FailingCloseConsumer::pollStarted),
                Duration.ofSeconds(2));

        KafkaRpcResourceException failure = assertThrows(KafkaRpcResourceException.class, gateway::close);

        assertEquals(1, producer.closeCount());
        assertTrue(consumers.stream().allMatch(consumer -> consumer.closeCount() == 1));
        assertEquals(2, failure.getSuppressed().length);
        assertSecretAbsent(failure);
        gateway.close();
        assertEquals(1, producer.closeCount());
    }

    private ApacheKafkaRpcMessageGateway gateway(
            final Duration closeTimeout,
            final KafkaRpcClientFactory factory) {
        return new ApacheKafkaRpcMessageGateway(
                settings(closeTimeout, Map.of(), Map.of()),
                RpcTransportObserver.noop(),
                factory);
    }

    private KafkaRpcSettings settings(
            final Duration closeTimeout,
            final Map<String, Object> producerProperties,
            final Map<String, Object> consumerProperties) {
        return new KafkaRpcSettings(
                BOOTSTRAP_SECRET,
                "client-secret",
                "consumer-group-secret",
                "topic-prefix-secret",
                "reply-secret",
                16,
                Duration.ofMillis(10),
                closeTimeout,
                producerProperties,
                consumerProperties);
    }

    /**
     * 创建不会在测试关闭前自然超时的 pending 请求。
     *
     * @param correlationId 请求关联 ID；不可为空。
     * @return 测试请求；不可为空，线程安全。
     */
    private RpcRequest request(final String correlationId) {
        return request(correlationId, Duration.ofSeconds(30).toMillis());
    }

    /**
     * 创建指定超时预算的 pending 请求。
     *
     * @param correlationId 请求关联 ID；不可为空。
     * @param timeoutMillis 从当前时刻开始的超时预算，单位毫秒；必须为正数。
     * @return 测试请求；不可为空，线程安全。
     */
    private RpcRequest request(final String correlationId, final long timeoutMillis) {
        return new RpcRequest(
                correlationId,
                "reply",
                "service",
                "method",
                "trace",
                Instant.now().plusMillis(timeoutMillis),
                RpcMode.REQUEST_RESPONSE,
                new byte[0]);
    }

    /**
     * 判断事件是否代表 pending future 的终态通知。
     *
     * @param type 传输事件类型；不可为空。
     * @return true 表示该事件对应 future 终态；线程安全。
     */
    private boolean terminalEvent(final RpcTransportEventType type) {
        return type == RpcTransportEventType.PENDING_COMPLETED
                || type == RpcTransportEventType.PENDING_FAILED
                || type == RpcTransportEventType.PENDING_TIMED_OUT
                || type == RpcTransportEventType.PENDING_REJECTED;
    }

    private int aliveTimeoutWorkers() {
        return (int) Thread.getAllStackTraces().keySet().stream()
                .filter(Thread::isAlive)
                .filter(thread -> "zero-rpc-kafka-pending-time-wheel".equals(thread.getName()))
                .count();
    }

    private void assertSecretAbsent(final Throwable failure) {
        StringWriter stackTrace = new StringWriter();
        failure.printStackTrace(new PrintWriter(stackTrace));
        for (String secret : List.of(
                BOOTSTRAP_SECRET,
                PRODUCER_SECRET,
                CONSUMER_SECRET,
                FAILURE_SECRET,
                "client-secret",
                "consumer-group-secret",
                "topic-prefix-secret",
                "reply-secret")) {
            assertFalse(failure.toString().contains(secret));
            assertFalse(String.valueOf(failure.getMessage()).contains(secret));
            assertFalse(stackTrace.toString().contains(secret));
        }
        assertNull(failure.getCause());
        for (Throwable suppressed : failure.getSuppressed()) {
            assertSame(KafkaRpcResourceException.class, suppressed.getClass());
            assertSecretAbsent(suppressed);
        }
    }

    private void assertEventually(
            final Supplier<Boolean> condition,
            final Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.get());
    }

    /**
     * 在不改变探针接口签名的前提下等待并发测试信号。
     *
     * @param latch 待等待信号；不可为空。
     * @throws AssertionError 当前测试线程被中断时抛出。
     */
    private void awaitLatchUnchecked(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("concurrency probe interrupted", failure);
        }
    }

    /**
     * 可在指定 request 订阅与回滚次数注入 {@link Error} 的 handler replay 测试网关。
     *
     * @author zn
     */
    private static final class ReplayFailingGateway implements KafkaRpcMessageGateway {

        /** 注入失败的 request subscribe 调用序号。 */
        private final int failingRequestSubscribeAttempt;

        /** 注入失败的 request unsubscribe 调用序号。 */
        private final int failingRequestUnsubscribeAttempt;

        /** request subscribe 调用次数。 */
        private final AtomicInteger requestSubscribeCount = new AtomicInteger();

        /** request unsubscribe 调用次数。 */
        private final AtomicInteger requestUnsubscribeCount = new AtomicInteger();

        /** reply unsubscribe 调用次数。 */
        private final AtomicInteger replyUnsubscribeCount = new AtomicInteger();

        /** 网关 close 调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 有序资源动作。 */
        private final List<String> events = new CopyOnWriteArrayList<>();

        /**
         * 创建 handler replay 故障注入网关。
         *
         * @param failingRequestSubscribeAttempt 注入 request subscribe 失败的调用序号。
         * @param failingRequestUnsubscribeAttempt 注入 request unsubscribe 失败的调用序号。
         */
        private ReplayFailingGateway(
                final int failingRequestSubscribeAttempt,
                final int failingRequestUnsubscribeAttempt) {
            this.failingRequestSubscribeAttempt = failingRequestSubscribeAttempt;
            this.failingRequestUnsubscribeAttempt = failingRequestUnsubscribeAttempt;
        }

        /**
         * 返回立即成功的发送阶段。
         *
         * @param message Kafka 消息；不可为空。
         * @return 已完成阶段；不可为空，线程安全。
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> send(final KafkaRpcMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 记录 adapter 默认 reply topic 订阅。
         *
         * @param topic reply topic；不可为空。
         * @param listener reply 监听器；不可为空。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
            events.add("reply-subscribe");
        }

        /**
         * 记录 request 订阅，并在配置的调用序号抛出带敏感标记的 {@link AssertionError}。
         *
         * @param topic request topic；不可为空。
         * @param group request consumer group；不可为空。
         * @param listener request 监听器；不可为空。
         * @throws AssertionError 到达故障注入调用序号时抛出。
         */
        @Override
        public void subscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
            int attempt = requestSubscribeCount.incrementAndGet();
            events.add("request-subscribe-" + attempt);
            if (attempt == failingRequestSubscribeAttempt) {
                throw new AssertionError(FAILURE_SECRET + "-request-subscribe");
            }
        }

        /**
         * 记录 adapter 默认 reply topic 取消订阅。
         *
         * @param topic reply topic；不可为空。
         * @param listener reply 监听器；不可为空。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
            replyUnsubscribeCount.incrementAndGet();
            events.add("reply-unsubscribe");
        }

        /**
         * 记录 request 取消订阅，并在配置的调用序号抛出带敏感标记的 {@link AssertionError}。
         *
         * @param topic request topic；不可为空。
         * @param group request consumer group；不可为空。
         * @param listener request 监听器；不可为空。
         * @throws AssertionError 到达故障注入调用序号时抛出。
         */
        @Override
        public void unsubscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
            int attempt = requestUnsubscribeCount.incrementAndGet();
            events.add("request-unsubscribe-" + attempt);
            if (attempt == failingRequestUnsubscribeAttempt) {
                throw new AssertionError(FAILURE_SECRET + "-request-unsubscribe");
            }
        }

        /** 记录网关关闭。 */
        @Override
        public void close() {
            closeCount.incrementAndGet();
            events.add("gateway-close");
        }

        /**
         * 返回 request subscribe 调用次数。
         *
         * @return 调用次数；线程安全。
         */
        private int requestSubscribeCount() {
            return requestSubscribeCount.get();
        }

        /**
         * 返回 request unsubscribe 调用次数。
         *
         * @return 调用次数；线程安全。
         */
        private int requestUnsubscribeCount() {
            return requestUnsubscribeCount.get();
        }

        /**
         * 返回 reply unsubscribe 调用次数。
         *
         * @return 调用次数；线程安全。
         */
        private int replyUnsubscribeCount() {
            return replyUnsubscribeCount.get();
        }

        /**
         * 返回 close 调用次数。
         *
         * @return 调用次数；线程安全。
         */
        private int closeCount() {
            return closeCount.get();
        }

        /**
         * 返回资源动作只读快照。
         *
         * @return 有序、不可变、非空动作列表；线程安全。
         */
        private List<String> events() {
            return List.copyOf(events);
        }
    }

    /**
     * 构造阶段订阅、回滚和关闭都会失败的测试网关。
     *
     * @author zn
     */
    private static final class ConstructionFailingGateway implements KafkaRpcMessageGateway {

        /** 是否使用 {@link AssertionError} 注入失败。 */
        private final boolean failWithError;

        /** 取消订阅调用次数。 */
        private final AtomicInteger unsubscribeCount = new AtomicInteger();

        /** 关闭调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 资源动作顺序。 */
        private final List<String> events = new CopyOnWriteArrayList<>();

        /** 创建使用运行时异常注入失败的网关。 */
        private ConstructionFailingGateway() {
            this(false);
        }

        /**
         * 创建构造失败网关。
         *
         * @param failWithError true 表示使用 {@link AssertionError}，false 表示使用运行时异常。
         */
        private ConstructionFailingGateway(final boolean failWithError) {
            this.failWithError = failWithError;
        }

        /**
         * 返回立即成功的发送阶段。
         *
         * @param message Kafka 消息；不可为空。
         * @return 已完成阶段；不可为空；线程安全。
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> send(final KafkaRpcMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 记录订阅后模拟第三方订阅失败。
         *
         * @param topic topic；不可为空。
         * @param listener 监听器；不可为空。
         * @throws IllegalStateException 始终抛出带敏感标记的测试异常。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
            events.add("subscribe");
            throwFailure("subscribe");
        }

        /**
         * 记录回滚后模拟第三方取消订阅失败。
         *
         * @param topic topic；不可为空。
         * @param listener 监听器；不可为空。
         * @throws IllegalStateException 始终抛出带敏感标记的测试异常。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
            unsubscribeCount.incrementAndGet();
            events.add("unsubscribe");
            throwFailure("unsubscribe");
        }

        /**
         * 记录关闭后模拟第三方网关关闭失败。
         *
         * @throws IllegalStateException 始终抛出带敏感标记的测试异常。
         */
        @Override
        public void close() {
            closeCount.incrementAndGet();
            events.add("close");
            throwFailure("close");
        }

        int unsubscribeCount() {
            return unsubscribeCount.get();
        }

        int closeCount() {
            return closeCount.get();
        }

        List<String> events() {
            return List.copyOf(events);
        }

        /**
         * 按测试模式抛出带敏感标记的非受检失败。
         *
         * @param phase 失败阶段；不可为空。
         * @throws AssertionError Error 注入模式下抛出。
         * @throws IllegalStateException 运行时异常注入模式下抛出。
         */
        private void throwFailure(final String phase) {
            if (failWithError) {
                throw new AssertionError(FAILURE_SECRET + "-" + phase);
            }
            throw new IllegalStateException(FAILURE_SECRET + "-" + phase);
        }
    }

    /**
     * 关闭阶段失败且记录全部尝试的测试网关。
     *
     * @author zn
     */
    private static final class CloseFailingGateway implements KafkaRpcMessageGateway {

        /** 是否使用 {@link AssertionError} 注入关闭失败。 */
        private final boolean failWithError;

        /** 取消订阅调用次数。 */
        private final AtomicInteger unsubscribeCount = new AtomicInteger();

        /** 关闭调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 是否开始注入关闭失败。 */
        private volatile boolean failCloseOperations;

        /** 创建使用运行时异常注入关闭失败的网关。 */
        private CloseFailingGateway() {
            this(false);
        }

        /**
         * 创建关闭失败网关。
         *
         * @param failWithError true 表示使用 {@link AssertionError}，false 表示使用运行时异常。
         */
        private CloseFailingGateway(final boolean failWithError) {
            this.failWithError = failWithError;
        }

        /**
         * 返回立即成功的发送阶段。
         *
         * @param message Kafka 消息；不可为空。
         * @return 已完成阶段；不可为空；线程安全。
         */
        @Override
        public java.util.concurrent.CompletionStage<Void> send(final KafkaRpcMessage message) {
            return CompletableFuture.completedFuture(null);
        }

        /**
         * 接受默认 group 订阅。
         *
         * @param topic topic；不可为空。
         * @param listener 监听器；不可为空。
         */
        @Override
        public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
        }

        /**
         * 接受显式 group 订阅。
         *
         * @param topic topic；不可为空。
         * @param group consumer group；不可为空。
         * @param listener 监听器；不可为空。
         */
        @Override
        public void subscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
        }

        /**
         * 记录默认 group 取消订阅，并按开关模拟失败。
         *
         * @param topic topic；不可为空。
         * @param listener 监听器；不可为空。
         * @throws IllegalStateException 当失败开关开启时抛出。
         */
        @Override
        public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
            failUnsubscribe();
        }

        /**
         * 记录显式 group 取消订阅，并按开关模拟失败。
         *
         * @param topic topic；不可为空。
         * @param group consumer group；不可为空。
         * @param listener 监听器；不可为空。
         * @throws IllegalStateException 当失败开关开启时抛出。
         */
        @Override
        public void unsubscribe(
                final String topic,
                final String group,
                final KafkaRpcMessageListener listener) {
            failUnsubscribe();
        }

        /**
         * 记录网关关闭，并按开关模拟失败。
         *
         * @throws IllegalStateException 当失败开关开启时抛出。
         */
        @Override
        public void close() {
            closeCount.incrementAndGet();
            if (failCloseOperations) {
                throwFailure("close");
            }
        }

        void failCloseOperations() {
            failCloseOperations = true;
        }

        int unsubscribeCount() {
            return unsubscribeCount.get();
        }

        int closeCount() {
            return closeCount.get();
        }

        private void failUnsubscribe() {
            unsubscribeCount.incrementAndGet();
            if (failCloseOperations) {
                throwFailure("unsubscribe");
            }
        }

        /**
         * 按测试模式抛出带敏感标记的非受检失败。
         *
         * @param phase 失败阶段；不可为空。
         * @throws AssertionError Error 注入模式下抛出。
         * @throws IllegalStateException 运行时异常注入模式下抛出。
         */
        private void throwFailure(final String phase) {
            if (failWithError) {
                throw new AssertionError(FAILURE_SECRET + "-" + phase);
            }
            throw new IllegalStateException(FAILURE_SECRET + "-" + phase);
        }
    }

    /**
     * 可注入 producer 与 consumer 的测试客户端工厂。
     *
     * @author zn
     */
    private static final class TestClientFactory implements KafkaRpcClientFactory {

        /** 测试 producer。 */
        private final Producer<String, byte[]> producer;

        /** consumer 提供器。 */
        private final Supplier<Consumer<String, byte[]>> consumers;

        TestClientFactory(
                final Producer<String, byte[]> producer,
                final Supplier<Consumer<String, byte[]>> consumers) {
            this.producer = producer;
            this.consumers = consumers;
        }

        /**
         * 返回预置 producer。
         *
         * @param properties producer 配置；不可为空。
         * @return producer；不可为空；线程安全。
         */
        @Override
        public Producer<String, byte[]> createProducer(final Properties properties) {
            return producer;
        }

        /**
         * 创建下一测试 consumer。
         *
         * @param properties consumer 配置；不可为空。
         * @return consumer；不可为空；线程不安全，仅归属 worker。
         */
        @Override
        public Consumer<String, byte[]> createConsumer(final Properties properties) {
            return consumers.get();
        }
    }

    /**
     * 忽略 wakeup 并阻塞到测试显式释放的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class BlockingConsumer extends MockConsumer<String, byte[]> {

        /** poll 已进入计数。 */
        private final CountDownLatch pollsStarted;

        /** poll 释放信号。 */
        private final CountDownLatch releasePolls;

        BlockingConsumer(final CountDownLatch pollsStarted, final CountDownLatch releasePolls) {
            super(OffsetResetStrategy.LATEST);
            this.pollsStarted = pollsStarted;
            this.releasePolls = releasePolls;
        }

        /**
         * 阻塞 poll 直到测试显式释放。
         *
         * @param timeout 调用方 poll 超时；不可为空，本测试替身忽略该值。
         * @return 空记录集；不可为空；线程不安全。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollsStarted.countDown();
            try {
                releasePolls.await();
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
            return ConsumerRecords.empty();
        }
    }

    /**
     * close 失败并记录调用次数的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class FailingCloseConsumer extends MockConsumer<String, byte[]> {

        /** 是否已经由所属 worker 进入 poll。 */
        private final AtomicBoolean pollStarted = new AtomicBoolean();

        /** close 调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 是否使用 {@link AssertionError} 注入关闭失败。 */
        private final boolean failWithError;

        FailingCloseConsumer() {
            this(false);
        }

        /**
         * 创建关闭失败 consumer。
         *
         * @param failWithError true 表示使用 {@link AssertionError}，false 表示使用 Kafka 异常。
         */
        FailingCloseConsumer(final boolean failWithError) {
            super(OffsetResetStrategy.LATEST);
            this.failWithError = failWithError;
        }

        /**
         * 记录 poll 已进入并委托给内存 consumer。
         *
         * @param timeout 调用方 poll 超时；不可为空，本测试替身使用零等待。
         * @return 空记录集；不可为空；线程不安全。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollStarted.set(true);
            return super.poll(Duration.ZERO);
        }

        /**
         * 判断所属 worker 是否已经进入过 poll。
         *
         * @return true 表示 worker 已开始消费循环；线程安全。
         */
        boolean pollStarted() {
            return pollStarted.get();
        }

        /**
         * 记录 close 并模拟 consumer 第三方关闭失败。
         *
         * @param options 关闭选项；不可为空，包含共享截止时间的剩余预算。
         * @throws KafkaException 始终抛出带敏感标记的测试异常。
         */
        @Override
        public synchronized void close(final CloseOptions options) {
            closeCount.incrementAndGet();
            if (failWithError) {
                throw new AssertionError(FAILURE_SECRET + "-consumer-close");
            }
            throw new KafkaException(FAILURE_SECRET + "-consumer-close");
        }

        int closeCount() {
            return closeCount.get();
        }
    }

    /**
     * close 失败并记录调用次数的 producer。
     *
     * @author zn
     */
    private static final class FailingCloseProducer extends MockProducer<String, byte[]> {

        /** close 调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /** 是否使用 {@link AssertionError} 注入关闭失败。 */
        private final boolean failWithError;

        /** 创建使用 Kafka 异常注入关闭失败的 producer。 */
        private FailingCloseProducer() {
            this(false);
        }

        /**
         * 创建关闭失败 producer。
         *
         * @param failWithError true 表示使用 {@link AssertionError}，false 表示使用 Kafka 异常。
         */
        private FailingCloseProducer(final boolean failWithError) {
            this.failWithError = failWithError;
        }

        /**
         * 记录 close 并模拟 producer 第三方关闭失败。
         *
         * @param timeout 关闭预算；不可为空。
         * @throws KafkaException 始终抛出带敏感标记的测试异常。
         */
        @Override
        public void close(final Duration timeout) {
            closeCount.incrementAndGet();
            if (failWithError) {
                throw new AssertionError(FAILURE_SECRET + "-producer-close");
            }
            throw new KafkaException(FAILURE_SECRET + "-producer-close");
        }

        int closeCount() {
            return closeCount.get();
        }
    }
}
