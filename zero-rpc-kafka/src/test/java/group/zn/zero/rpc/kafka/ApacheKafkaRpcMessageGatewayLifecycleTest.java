package group.zn.zero.rpc.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.MockConsumer;
import org.apache.kafka.clients.consumer.OffsetResetStrategy;
import org.apache.kafka.clients.producer.MockProducer;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.KafkaException;
import org.junit.jupiter.api.Test;

/**
 * Apache Kafka RPC gateway worker 重启与退休生命周期测试。
 *
 * @author zn
 */
class ApacheKafkaRpcMessageGatewayLifecycleTest {

    /** 测试使用的第三方敏感异常标记。 */
    private static final String FAILURE_SECRET = "gateway-lifecycle-secret";

    /**
     * 验证 restart observer 抛出 {@link Error} 后 consumer worker 仍会创建并运行下一 consumer。
     *
     * @throws InterruptedException 等待 worker 重启与关闭时被中断。
     */
    @Test
    void restartObserverErrorShouldNotKillConsumerWorker() throws InterruptedException {
        CountDownLatch firstPollFailed = new CountDownLatch(1);
        CountDownLatch restartObserved = new CountDownLatch(1);
        CountDownLatch healthyPollStarted = new CountDownLatch(1);
        AtomicInteger consumerCreations = new AtomicInteger();
        AtomicInteger restartNotifications = new AtomicInteger();
        List<MockConsumer<String, byte[]>> consumers = new CopyOnWriteArrayList<>();
        MockProducer<String, byte[]> producer = new MockProducer<>();
        TestClientFactory factory = new TestClientFactory(producer, () -> {
            int attempt = consumerCreations.incrementAndGet();
            MockConsumer<String, byte[]> consumer = attempt == 1
                    ? new FirstPollFailingConsumer(firstPollFailed)
                    : new HealthyPollingConsumer(healthyPollStarted);
            consumers.add(consumer);
            return consumer;
        });
        RpcTransportObserver observer = event -> {
            if (event.type() == RpcTransportEventType.CONSUMER_RESTARTING) {
                restartNotifications.incrementAndGet();
                restartObserved.countDown();
                throw new AssertionError(FAILURE_SECRET + "-restart-observer");
            }
        };
        ApacheKafkaRpcMessageGateway gateway = new ApacheKafkaRpcMessageGateway(
                settings(),
                observer,
                factory);

        gateway.subscribe("restart-topic", "restart-group", message -> { });

        assertTrue(firstPollFailed.await(2, TimeUnit.SECONDS));
        assertTrue(restartObserved.await(2, TimeUnit.SECONDS));
        assertTrue(healthyPollStarted.await(2, TimeUnit.SECONDS));
        assertEquals(2, consumerCreations.get());
        assertEquals(1, restartNotifications.get());

        gateway.close();

        assertTrue(producer.closed());
        assertTrue(consumers.stream().allMatch(MockConsumer::closed));
    }

    /**
     * 验证 close 会接管并等待已从订阅表分离、但 unsubscribe 尚未停止的退休 worker。
     *
     * @throws InterruptedException 等待并发生命周期信号时被中断。
     */
    @Test
    void closeShouldAwaitWorkerRetiringBeforeUnsubscribeStop() throws InterruptedException {
        CountDownLatch pollsStarted = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        CountDownLatch beforeRetiredStop = new CountDownLatch(1);
        CountDownLatch allowRetiredStop = new CountDownLatch(1);
        CountDownLatch closeSnapshot = new CountDownLatch(1);
        CountDownLatch closeReturned = new CountDownLatch(1);
        AtomicInteger snapshotWorkerCount = new AtomicInteger(-1);
        ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe probe =
                new ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe() {
                    /** 该测试不暂停 worker 发布。 */
                    @Override
                    public void beforeWorkerPublication() {
                    }

                    /** 在 worker 分离后、锁外停止前建立确定性竞争窗口。 */
                    @Override
                    public void beforeRetiredWorkerStop() {
                        beforeRetiredStop.countDown();
                        awaitLatchUnchecked(allowRetiredStop);
                    }

                    /**
                     * 记录 close 取得的 active + retiring worker 快照。
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
        BlockingConsumer consumer = new BlockingConsumer(pollsStarted, releasePoll);
        TestClientFactory factory = new TestClientFactory(producer, () -> consumer);
        ApacheKafkaRpcMessageGateway gateway = new ApacheKafkaRpcMessageGateway(
                settings(),
                RpcTransportObserver.noop(),
                factory,
                probe);
        KafkaRpcMessageListener listener = message -> { };
        gateway.subscribe("retiring-topic", "retiring-group", listener);
        assertTrue(pollsStarted.await(2, TimeUnit.SECONDS));
        AtomicReference<Throwable> unsubscribeFailure = new AtomicReference<>();
        Thread unsubscriber = Thread.ofVirtual().name("kafka-retiring-unsubscriber").start(() -> {
            try {
                gateway.unsubscribe("retiring-topic", "retiring-group", listener);
            } catch (RuntimeException | Error failure) {
                unsubscribeFailure.set(failure);
            }
        });
        boolean retirementObserved = beforeRetiredStop.await(2, TimeUnit.SECONDS);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        Thread closer = Thread.ofVirtual().name("kafka-retiring-closer").start(() -> {
            try {
                gateway.close();
            } catch (RuntimeException | Error failure) {
                closeFailure.set(failure);
            } finally {
                closeReturned.countDown();
            }
        });
        boolean snapshotObserved = closeSnapshot.await(2, TimeUnit.SECONDS);
        boolean closeWaitedForWorker = !closeReturned.await(100, TimeUnit.MILLISECONDS);
        try {
            releasePoll.countDown();
            closer.join(2_000L);
        } finally {
            releasePoll.countDown();
            allowRetiredStop.countDown();
            closer.join(2_000L);
            unsubscriber.join(2_000L);
        }

        assertTrue(retirementObserved);
        assertTrue(snapshotObserved);
        assertTrue(closeWaitedForWorker);
        assertEquals(1, snapshotWorkerCount.get());
        assertFalse(closer.isAlive());
        assertFalse(unsubscriber.isAlive());
        assertNull(closeFailure.get());
        assertNull(unsubscribeFailure.get());
        assertTrue(consumer.closed());
        assertTrue(producer.closed());
    }

    /**
     * 验证 unsubscribe 超时后仍存活的退休 worker 不会丢失，后续 close 必须纳入快照并报告。
     *
     * @throws InterruptedException 等待阻塞 consumer 启动与最终退出时被中断。
     */
    @Test
    void unsubscribeTimeoutShouldKeepLiveWorkerOwnedUntilClose() throws InterruptedException {
        CountDownLatch pollStarted = new CountDownLatch(1);
        CountDownLatch releasePoll = new CountDownLatch(1);
        AtomicInteger snapshotWorkerCount = new AtomicInteger(-1);
        ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe probe =
                new ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe() {
                    /** 该测试不暂停 worker 发布。 */
                    @Override
                    public void beforeWorkerPublication() {
                    }

                    /** 该测试不暂停退休 worker stop。 */
                    @Override
                    public void beforeRetiredWorkerStop() {
                    }

                    /**
                     * 记录 close 是否仍拥有 unsubscribe 超时的 worker。
                     *
                     * @param workerCount 本次 close 快照内的 worker 数量。
                     */
                    @Override
                    public void afterCloseSnapshot(final int workerCount) {
                        snapshotWorkerCount.set(workerCount);
                    }
                };
        MockProducer<String, byte[]> producer = new MockProducer<>();
        BlockingConsumer consumer = new BlockingConsumer(pollStarted, releasePoll);
        ApacheKafkaRpcMessageGateway gateway = new ApacheKafkaRpcMessageGateway(
                settings(Duration.ofMillis(100)),
                RpcTransportObserver.noop(),
                new TestClientFactory(producer, () -> consumer),
                probe);
        KafkaRpcMessageListener listener = message -> { };
        gateway.subscribe("timeout-retiring-topic", "timeout-retiring-group", listener);
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS));

        KafkaRpcResourceException unsubscribeFailure = assertThrows(
                KafkaRpcResourceException.class,
                () -> gateway.unsubscribe(
                        "timeout-retiring-topic",
                        "timeout-retiring-group",
                        listener));

        assertEquals("kafka rpc consumer close deadline exceeded", unsubscribeFailure.getMessage());
        assertFalse(consumer.closed());
        KafkaRpcResourceException closeFailure;
        try {
            closeFailure = assertThrows(KafkaRpcResourceException.class, gateway::close);
        } finally {
            releasePoll.countDown();
        }

        assertEquals(1, snapshotWorkerCount.get());
        assertEquals("kafka rpc consumer close deadline exceeded", closeFailure.getMessage());
        assertTrue(producer.closed());
        assertEventually(consumer::closed, Duration.ofSeconds(2));
        gateway.close();
    }

    /**
     * 验证 worker 已终止但 consumer close 失败时会结束退休所有权，避免后续 gateway close 重复处理。
     *
     * @throws InterruptedException 等待 consumer worker 启动时被中断。
     */
    @Test
    void terminatedWorkerCloseFailureShouldNotRemainRetiring() throws InterruptedException {
        CountDownLatch pollStarted = new CountDownLatch(1);
        AtomicInteger snapshotWorkerCount = new AtomicInteger(-1);
        ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe probe =
                new ApacheKafkaRpcMessageGateway.WorkerLifecycleProbe() {
                    /** 该测试不暂停 worker 发布。 */
                    @Override
                    public void beforeWorkerPublication() {
                    }

                    /** 该测试不暂停退休 worker stop。 */
                    @Override
                    public void beforeRetiredWorkerStop() {
                    }

                    /**
                     * 记录后续 close 是否重复持有已终止 worker。
                     *
                     * @param workerCount 本次 close 快照内的 worker 数量。
                     */
                    @Override
                    public void afterCloseSnapshot(final int workerCount) {
                        snapshotWorkerCount.set(workerCount);
                    }
                };
        MockProducer<String, byte[]> producer = new MockProducer<>();
        CloseFailingConsumer consumer = new CloseFailingConsumer(pollStarted);
        ApacheKafkaRpcMessageGateway gateway = new ApacheKafkaRpcMessageGateway(
                settings(),
                RpcTransportObserver.noop(),
                new TestClientFactory(producer, () -> consumer),
                probe);
        KafkaRpcMessageListener listener = message -> { };
        gateway.subscribe("close-failure-topic", "close-failure-group", listener);
        assertTrue(pollStarted.await(2, TimeUnit.SECONDS));

        KafkaRpcResourceException failure = assertThrows(
                KafkaRpcResourceException.class,
                () -> gateway.unsubscribe("close-failure-topic", "close-failure-group", listener));

        assertEquals("close kafka rpc consumer failed", failure.getMessage());
        assertEquals(1, consumer.closeCount());
        gateway.close();
        assertEquals(0, snapshotWorkerCount.get());
        assertEquals(1, consumer.closeCount());
        assertTrue(producer.closed());
    }

    /**
     * 创建 gateway 生命周期测试配置。
     *
     * @return 非空、不可变、线程安全配置。
     */
    private KafkaRpcSettings settings() {
        return settings(Duration.ofSeconds(1));
    }

    /**
     * 创建指定关闭预算的 gateway 生命周期测试配置。
     *
     * @param closeTimeout gateway 总关闭预算；不可为空、不可为负。
     * @return 非空、不可变、线程安全配置。
     */
    private KafkaRpcSettings settings(final Duration closeTimeout) {
        return new KafkaRpcSettings(
                "localhost:9092",
                "gateway-lifecycle-client",
                "gateway-lifecycle-group",
                "gateway.lifecycle",
                "gateway-lifecycle-reply",
                8,
                Duration.ofMillis(10),
                closeTimeout,
                Map.of(),
                Map.of());
    }

    /**
     * 在预算内等待条件成立。
     *
     * @param condition 待验证条件；不可为空。
     * @param timeout 最大等待预算；不可为空、不可为负。
     * @throws InterruptedException 等待期间被中断时抛出。
     */
    private static void assertEventually(
            final Supplier<Boolean> condition,
            final Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10L);
        }
        assertTrue(condition.get());
    }

    /**
     * 在不向测试探针增加受检异常的前提下等待信号。
     *
     * @param latch 待等待信号；不可为空。
     * @throws AssertionError 当前线程被中断时抛出。
     */
    private static void awaitLatchUnchecked(final CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError("gateway lifecycle probe interrupted", failure);
        }
    }

    /**
     * 使用预置 producer 与 consumer supplier 的测试客户端工厂。
     *
     * @author zn
     */
    private static final class TestClientFactory implements KafkaRpcClientFactory {

        /** 测试 producer。 */
        private final Producer<String, byte[]> producer;

        /** consumer supplier。 */
        private final Supplier<Consumer<String, byte[]>> consumers;

        /**
         * 创建测试客户端工厂。
         *
         * @param producer 测试 producer；不可为空。
         * @param consumers consumer supplier；不可为空。
         */
        private TestClientFactory(
                final Producer<String, byte[]> producer,
                final Supplier<Consumer<String, byte[]>> consumers) {
            this.producer = producer;
            this.consumers = consumers;
        }

        /**
         * 返回预置 producer。
         *
         * @param properties producer properties；不可为空。
         * @return 预置 producer；不可为空，线程安全。
         */
        @Override
        public Producer<String, byte[]> createProducer(final Properties properties) {
            return producer;
        }

        /**
         * 创建下一 consumer。
         *
         * @param properties consumer properties；不可为空。
         * @return consumer；不可为空，归属调用 worker。
         */
        @Override
        public Consumer<String, byte[]> createConsumer(final Properties properties) {
            return consumers.get();
        }
    }

    /**
     * 首次 poll 固定失败的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class FirstPollFailingConsumer extends MockConsumer<String, byte[]> {

        /** poll 失败信号。 */
        private final CountDownLatch pollFailed;

        /**
         * 创建首次 poll 失败 consumer。
         *
         * @param pollFailed poll 失败信号；不可为空。
         */
        private FirstPollFailingConsumer(final CountDownLatch pollFailed) {
            super(OffsetResetStrategy.LATEST);
            this.pollFailed = pollFailed;
        }

        /**
         * 注入带敏感标记的第三方 poll 失败。
         *
         * @param timeout poll timeout；不可为空。
         * @return 不会正常返回。
         * @throws KafkaException 始终抛出。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollFailed.countDown();
            throw new KafkaException(FAILURE_SECRET + "-poll");
        }
    }

    /**
     * 记录健康 poll 已开始的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class HealthyPollingConsumer extends MockConsumer<String, byte[]> {

        /** 健康 poll 信号。 */
        private final CountDownLatch pollStarted;

        /**
         * 创建健康 consumer。
         *
         * @param pollStarted 健康 poll 信号；不可为空。
         */
        private HealthyPollingConsumer(final CountDownLatch pollStarted) {
            super(OffsetResetStrategy.LATEST);
            this.pollStarted = pollStarted;
        }

        /**
         * 记录 worker 已进入下一 consumer 的 poll。
         *
         * @param timeout poll timeout；不可为空。
         * @return 空记录集；不可为空，线程不安全。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollStarted.countDown();
            return super.poll(Duration.ZERO);
        }
    }

    /**
     * worker 可正常退出、但原生 consumer close 固定失败的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class CloseFailingConsumer extends MockConsumer<String, byte[]> {

        /** poll 开始信号。 */
        private final CountDownLatch pollStarted;

        /** close 调用次数。 */
        private final AtomicInteger closeCount = new AtomicInteger();

        /**
         * 创建 close 失败 consumer。
         *
         * @param pollStarted poll 开始信号；不可为空。
         */
        private CloseFailingConsumer(final CountDownLatch pollStarted) {
            super(OffsetResetStrategy.LATEST);
            this.pollStarted = pollStarted;
        }

        /**
         * 记录 worker 已进入 poll。
         *
         * @param timeout poll timeout；不可为空。
         * @return 空记录集；不可为空，线程不安全。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollStarted.countDown();
            return super.poll(Duration.ZERO);
        }

        /**
         * 注入原生 consumer close 失败。
         *
         * @param options close options；不可为空。
         * @throws KafkaException 始终抛出固定测试失败。
         */
        @Override
        public synchronized void close(final CloseOptions options) {
            closeCount.incrementAndGet();
            throw new KafkaException(FAILURE_SECRET + "-consumer-close");
        }

        /**
         * 返回 close 调用次数。
         *
         * @return 调用次数；线程安全。
         */
        private int closeCount() {
            return closeCount.get();
        }
    }

    /**
     * 忽略 wakeup 并等待测试显式释放的 consumer。
     *
     * @author zn
     */
    @SuppressWarnings("deprecation")
    private static final class BlockingConsumer extends MockConsumer<String, byte[]> {

        /** poll 开始信号。 */
        private final CountDownLatch pollStarted;

        /** poll 释放信号。 */
        private final CountDownLatch releasePoll;

        /**
         * 创建阻塞 consumer。
         *
         * @param pollStarted poll 开始信号；不可为空。
         * @param releasePoll poll 释放信号；不可为空。
         */
        private BlockingConsumer(
                final CountDownLatch pollStarted,
                final CountDownLatch releasePoll) {
            super(OffsetResetStrategy.LATEST);
            this.pollStarted = pollStarted;
            this.releasePoll = releasePoll;
        }

        /**
         * 阻塞直到测试显式释放。
         *
         * @param timeout poll timeout；不可为空。
         * @return 空记录集；不可为空，线程不安全。
         */
        @Override
        public ConsumerRecords<String, byte[]> poll(final Duration timeout) {
            pollStarted.countDown();
            awaitLatchUnchecked(releasePoll);
            return ConsumerRecords.empty();
        }
    }
}
