package group.zn.zero.rpc.kafka;

import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.CloseOptions;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.common.errors.WakeupException;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

/**
 * Apache Kafka RPC 消息网关。
 *
 * @author zn
 */
final class ApacheKafkaRpcMessageGateway implements KafkaRpcMessageGateway {

    /**
     * 网关内部异常日志器。
     */
    private static final System.Logger LOGGER = System.getLogger(ApacheKafkaRpcMessageGateway.class.getName());

    /**
     * Kafka RPC 配置。
     */
    private final KafkaRpcSettings settings;

    /** 当前网关独占的 reply consumer group，避免多节点共享 group 丢失响应。 */
    private final String replyConsumerGroup;

    /**
     * RPC 传输观测器。
     */
    private final RpcTransportObserver observer;

    /**
     * Kafka producer。
     */
    private final Producer<String, byte[]> producer;

    /**
     * Kafka 原生客户端工厂。
     */
    private final KafkaRpcClientFactory clientFactory;

    /** 已拉取消息的业务交付策略；poll 线程永不直接调用监听器。 */
    private final KafkaRpcConsumerExecution consumerExecution;

    /** subscribe、unsubscribe 与 close 的线性化监视器；不进入消息发送热路径。 */
    private final Object workerLifecycleMonitor = new Object();

    /** 包级并发生命周期测试探针；生产构造器固定使用 no-op。 */
    private final WorkerLifecycleProbe workerLifecycleProbe;

    /**
     * 订阅到 consumer worker 的映射。
     */
    private final ConcurrentMap<Subscription, TopicWorker> workers = new ConcurrentHashMap<>();

    /** 已从订阅表分离、但 unsubscribe 尚未完成停止的 worker；由生命周期监视器保护。 */
    private final Set<TopicWorker> retiringWorkers = new HashSet<>();

    /**
     * 是否已经关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * 创建 Apache Kafka RPC 消息网关。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @throws NullPointerException 当配置为空时抛出。
     * @throws ZeroException 当 producer 创建失败时抛出。
     */
    ApacheKafkaRpcMessageGateway(final KafkaRpcSettings settings) {
        this(settings, RpcTransportObserver.noop());
    }

    /**
     * 创建 Apache Kafka RPC 消息网关。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @throws NullPointerException 当配置或 observer 为空时抛出。
     * @throws ZeroException 当 producer 创建失败时抛出。
     */
    ApacheKafkaRpcMessageGateway(final KafkaRpcSettings settings, final RpcTransportObserver observer) {
        this(settings, observer, KafkaRpcConsumerExecution.compatibilityDefault(), KafkaRpcClientFactory.APACHE,
                WorkerLifecycleProbe.noop());
    }

    /**
     * 使用指定业务交付策略创建 Apache Kafka RPC 消息网关。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @param consumerExecution consumer 业务交付策略；不可为空。
     */
    ApacheKafkaRpcMessageGateway(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer,
            final KafkaRpcConsumerExecution consumerExecution) {
        this(settings, observer, consumerExecution, KafkaRpcClientFactory.APACHE, WorkerLifecycleProbe.noop());
    }

    /**
     * 使用指定客户端工厂创建 Apache Kafka RPC 消息网关。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @param clientFactory Kafka 原生客户端工厂；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     * @throws ZeroException 当 producer 创建失败时抛出；异常图不会保留第三方配置值。
     */
    ApacheKafkaRpcMessageGateway(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer,
            final KafkaRpcClientFactory clientFactory) {
        this(settings, observer, KafkaRpcConsumerExecution.compatibilityDefault(), clientFactory,
                WorkerLifecycleProbe.noop());
    }

    /**
     * 使用指定客户端工厂与包级并发探针创建网关。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @param clientFactory Kafka 原生客户端工厂；不可为空。
     * @param workerLifecycleProbe worker 发布与 close 快照探针；不可为空，仅用于确定性并发测试。
     */
    ApacheKafkaRpcMessageGateway(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer,
            final KafkaRpcClientFactory clientFactory,
            final WorkerLifecycleProbe workerLifecycleProbe) {
        this(settings, observer, KafkaRpcConsumerExecution.compatibilityDefault(), clientFactory,
                workerLifecycleProbe);
    }

    /** 使用指定业务交付策略、客户端工厂与并发探针创建网关。 */
    ApacheKafkaRpcMessageGateway(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer,
            final KafkaRpcConsumerExecution consumerExecution,
            final KafkaRpcClientFactory clientFactory,
            final WorkerLifecycleProbe workerLifecycleProbe) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.replyConsumerGroup = settings.consumerGroupId() + "-reply-"
                + UUID.randomUUID().toString().replace("-", "");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.consumerExecution = Objects.requireNonNull(consumerExecution, "consumerExecution");
        this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        this.workerLifecycleProbe = Objects.requireNonNull(workerLifecycleProbe, "workerLifecycleProbe");
        try {
            this.producer = Objects.requireNonNull(
                    clientFactory.createProducer(producerProperties(settings)),
                    "producer");
        } catch (RuntimeException | Error ex) {
            throw KafkaRpcResourceException.sanitize("create kafka rpc producer failed", ex);
        }
    }

    /**
     * 发送 Kafka RPC 消息。
     *
     * @param message Kafka RPC 消息；不可为空。
     * @return 发送完成阶段；不可为空；线程安全。
     */
    @Override
    public CompletionStage<Void> send(final KafkaRpcMessage message) {
        KafkaRpcMessage current = Objects.requireNonNull(message, "message");
        if (closed.get()) {
            return failedFuture("kafka rpc gateway is closed");
        }
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            producer.send(new ProducerRecord<>(current.topic(), current.key(), current.value()),
                    (metadata, exception) -> {
                        if (exception == null) {
                            future.complete(null);
                        } else {
                            future.completeExceptionally(KafkaRpcResourceException.create(
                                    "send kafka rpc message failed"));
                        }
                    });
        } catch (RuntimeException ex) {
            future.completeExceptionally(KafkaRpcResourceException.sanitize(
                    "send kafka rpc message failed", ex));
        }
        return future;
    }

    /**
     * 订阅 Kafka topic。
     *
     * @param topic Kafka topic；不可为空。
     * @param listener 消息监听器；不可为空。
     */
    @Override
    public void subscribe(final String topic, final KafkaRpcMessageListener listener) {
        subscribe(topic, settings.consumerGroupId(), listener);
    }

    /** 使用进程独占 consumer group 订阅 reply topic。 */
    @Override
    public void subscribeReply(final String topic, final KafkaRpcMessageListener listener) {
        subscribe(topic, replyConsumerGroup, listener);
    }

    /**
     * 按 consumer group 订阅 Kafka topic。
     *
     * @param topic Kafka topic；不可为空。
     * @param group Kafka consumer group；可为空。
     * @param listener 消息监听器；不可为空。
     */
    @Override
    public void subscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
        synchronized (workerLifecycleMonitor) {
            requireOpen();
            try {
                workerLifecycleProbe.beforeWorkerPublication();
            } catch (RuntimeException | Error failure) {
                throw KafkaRpcResourceException.sanitize(
                        "prepare kafka rpc consumer worker publication failed",
                        failure);
            }
            subscribeLinearized(topic, group, listener);
        }
    }

    /**
     * 取消订阅 Kafka topic。
     *
     * @param topic Kafka topic；不可为空。
     * @param listener 消息监听器；不可为空。
     */
    @Override
    public void unsubscribe(final String topic, final KafkaRpcMessageListener listener) {
        unsubscribe(topic, settings.consumerGroupId(), listener);
    }

    /** 取消 reply topic 的进程独占订阅。 */
    @Override
    public void unsubscribeReply(final String topic, final KafkaRpcMessageListener listener) {
        unsubscribe(topic, replyConsumerGroup, listener);
    }

    /**
     * 按 consumer group 取消订阅 Kafka topic。
     *
     * @param topic Kafka topic；不可为空。
     * @param group Kafka consumer group；可为空。
     * @param listener 消息监听器；不可为空。
     */
    @Override
    public void unsubscribe(final String topic, final String group, final KafkaRpcMessageListener listener) {
        TopicWorker retiringWorker = null;
        synchronized (workerLifecycleMonitor) {
            Objects.requireNonNull(listener, "listener");
            Subscription subscription = new Subscription(
                    Objects.requireNonNull(topic, "topic"),
                    effectiveGroup(group));
            TopicWorker worker = workers.get(subscription);
            if (worker == null) {
                return;
            }
            worker.remove(listener);
            if (worker.empty() && workers.remove(subscription, worker)) {
                retiringWorkers.add(worker);
                retiringWorker = worker;
            }
        }
        if (retiringWorker != null) {
            KafkaRpcResourceException failure = null;
            try {
                workerLifecycleProbe.beforeRetiredWorkerStop();
            } catch (RuntimeException | Error probeFailure) {
                failure = KafkaRpcResourceException.sanitize(
                        "observe kafka rpc retired worker stop failed",
                        probeFailure);
            }
            KafkaRpcCloseDeadline deadline = KafkaRpcCloseDeadline.after(settings.closeTimeout());
            failure = stopWorker(retiringWorker, deadline, failure);
            synchronized (workerLifecycleMonitor) {
                if (retiringWorker.terminated()) {
                    retiringWorkers.remove(retiringWorker);
                }
            }
            if (failure != null) {
                throw failure;
            }
        }
    }

    /**
     * 关闭 Kafka 消息网关。
     */
    @Override
    public void close() {
        KafkaRpcCloseDeadline deadline;
        List<TopicWorker> closingWorkers;
        KafkaRpcResourceException failure = null;
        synchronized (workerLifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            deadline = KafkaRpcCloseDeadline.after(settings.closeTimeout());
            Set<TopicWorker> closingWorkerSet = new HashSet<>(workers.values());
            closingWorkerSet.addAll(retiringWorkers);
            closingWorkers = new ArrayList<>(closingWorkerSet);
            try {
                workerLifecycleProbe.afterCloseSnapshot(closingWorkers.size());
            } catch (RuntimeException | Error probeFailure) {
                failure = KafkaRpcResourceException.sanitize(
                        "observe kafka rpc close snapshot failed",
                        probeFailure);
            }
            workers.clear();
            retiringWorkers.clear();
        }
        failure = signalWorkers(closingWorkers, deadline, failure);
        failure = awaitWorkers(closingWorkers, deadline, failure);
        try {
            producer.close(deadline.remaining());
        } catch (RuntimeException | Error ex) {
            failure = KafkaRpcResourceException.merge(failure, ex, "close kafka rpc producer failed");
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void subscribeLinearized(
            final String topic,
            final String group,
            final KafkaRpcMessageListener listener) {
        Objects.requireNonNull(listener, "listener");
        Subscription subscription = new Subscription(
                Objects.requireNonNull(topic, "topic"),
                effectiveGroup(group));
        TopicWorker worker;
        try {
            worker = workers.computeIfAbsent(subscription, this::newWorker);
        } catch (RuntimeException | Error failure) {
            throw KafkaRpcResourceException.sanitize("create kafka rpc consumer worker failed", failure);
        }
        boolean listenerAdded = worker.add(listener);
        try {
            worker.start();
            rejectConcurrentClose();
        } catch (RuntimeException | Error failure) {
            throw rollbackFailedSubscription(subscription, worker, listener, listenerAdded, failure);
        }
    }

    private TopicWorker newWorker(final Subscription subscription) {
        return new TopicWorker(
                subscription,
                settings,
                consumerProperties(settings, subscription),
                observer,
                consumerExecution,
                clientFactory);
    }

    private Properties producerProperties(final KafkaRpcSettings settings) {
        Properties properties = new Properties();
        properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        properties.put(ProducerConfig.CLIENT_ID_CONFIG, settings.clientId() + "-producer");
        properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        properties.put(ProducerConfig.ACKS_CONFIG, "all");
        append(properties, settings.producerProperties());
        return properties;
    }

    private Properties consumerProperties(final KafkaRpcSettings settings, final Subscription subscription) {
        KafkaRpcTopicResolver resolver = new KafkaRpcTopicResolver(settings.topicPrefix());
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, settings.bootstrapServers());
        properties.put(ConsumerConfig.CLIENT_ID_CONFIG, settings.clientId()
                + "-consumer-" + resolver.sanitize(subscription.topic())
                + "-" + resolver.sanitize(subscription.group()));
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, subscription.group());
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class.getName());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        properties.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 100);
        properties.put(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG, 300_000);
        append(properties, settings.consumerProperties());
        // 处理确认是网关契约，扩展属性不得重新打开自动提交。
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        return properties;
    }

    private String effectiveGroup(final String group) {
        return group == null || group.isBlank() ? settings.consumerGroupId() : group;
    }

    private void append(final Properties properties, final Map<String, Object> values) {
        values.forEach(properties::put);
    }

    private void requireOpen() {
        if (closed.get()) {
            throw KafkaRpcResourceException.create("kafka rpc gateway is closed");
        }
    }

    private void rejectConcurrentClose() {
        if (closed.get()) {
            throw KafkaRpcResourceException.create("kafka rpc gateway closed during subscription");
        }
    }

    private KafkaRpcResourceException rollbackFailedSubscription(
            final Subscription subscription,
            final TopicWorker worker,
            final KafkaRpcMessageListener listener,
            final boolean listenerAdded,
            final Throwable failure) {
        KafkaRpcResourceException primary = KafkaRpcResourceException.sanitize(
                "start kafka rpc consumer worker failed",
                failure);
        if (listenerAdded) {
            worker.remove(listener);
        }
        if (worker.empty() && workers.remove(subscription, worker)) {
            KafkaRpcCloseDeadline deadline = KafkaRpcCloseDeadline.after(settings.closeTimeout());
            primary = stopWorker(worker, deadline, primary);
        }
        return primary;
    }

    private KafkaRpcResourceException signalWorkers(
            final List<TopicWorker> closingWorkers,
            final KafkaRpcCloseDeadline deadline,
            final KafkaRpcResourceException primary) {
        KafkaRpcResourceException failure = primary;
        for (TopicWorker worker : closingWorkers) {
            try {
                worker.requestStop(deadline);
            } catch (RuntimeException | Error ex) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        ex,
                        "signal kafka rpc consumer stop failed");
            }
        }
        return failure;
    }

    private KafkaRpcResourceException awaitWorkers(
            final List<TopicWorker> closingWorkers,
            final KafkaRpcCloseDeadline deadline,
            final KafkaRpcResourceException primary) {
        KafkaRpcResourceException failure = primary;
        for (TopicWorker worker : closingWorkers) {
            try {
                worker.awaitStop(deadline);
            } catch (RuntimeException | Error ex) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        ex,
                        "close kafka rpc consumer worker failed");
            }
        }
        return failure;
    }

    private KafkaRpcResourceException stopWorker(
            final TopicWorker worker,
            final KafkaRpcCloseDeadline deadline,
            final KafkaRpcResourceException primary) {
        KafkaRpcResourceException failure = primary;
        try {
            worker.requestStop(deadline);
        } catch (RuntimeException | Error ex) {
            failure = KafkaRpcResourceException.merge(
                    failure,
                    ex,
                    "signal kafka rpc consumer stop failed");
        }
        try {
            worker.awaitStop(deadline);
        } catch (RuntimeException | Error ex) {
            failure = KafkaRpcResourceException.merge(
                    failure,
                    ex,
                    "close kafka rpc consumer worker failed");
        }
        return failure;
    }

    private <T> CompletableFuture<T> failedFuture(final String message) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(KafkaRpcResourceException.create(message));
        return future;
    }

    /**
     * Kafka topic + consumer group 订阅键。
     *
     * @param topic Kafka topic。
     * @param group Kafka consumer group。
     */
    private record Subscription(String topic, String group) {

        /**
         * 创建订阅键。
         */
        private Subscription {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(group, "group");
        }
    }

    /**
     * worker 发布与 close 快照之间并发关系的包级测试探针。
     *
     * <p>生产构造器只使用 no-op 实现；回调运行在 {@link #workerLifecycleMonitor} 内，不允许执行生产远程 IO。
     *
     * @author zn
     */
    interface WorkerLifecycleProbe {

        /**
         * subscribe 通过 closed 检查后、发布 worker 前回调。
         */
        void beforeWorkerPublication();

        /**
         * unsubscribe 已从注册表分离空 worker、准备在生命周期锁外停止时回调。
         */
        void beforeRetiredWorkerStop();

        /**
         * close 获得 worker 快照后、从注册表分离 worker 前回调。
         *
         * @param workerCount 本次 close 快照内的 worker 数量。
         */
        void afterCloseSnapshot(int workerCount);

        /**
         * 返回生产 no-op 探针。
         *
         * @return 无副作用探针；不可为空，线程安全。
         */
        static WorkerLifecycleProbe noop() {
            return new WorkerLifecycleProbe() {
                /** worker 发布前不执行动作。 */
                @Override
                public void beforeWorkerPublication() {
                }

                /** 退休 worker 停止前不执行动作。 */
                @Override
                public void beforeRetiredWorkerStop() {
                }

                /**
                 * close 快照后不执行动作。
                 *
                 * @param workerCount 本次 close 快照内的 worker 数量。
                 */
                @Override
                public void afterCloseSnapshot(final int workerCount) {
                }
            };
        }
    }

    /**
     * 单 topic Kafka consumer worker。
     *
     * @author zn
     */
    private static final class TopicWorker {

        /**
         * Kafka 订阅。
         */
        private final Subscription subscription;

        /**
         * Kafka RPC 配置。
         */
        private final KafkaRpcSettings settings;

        /**
         * RPC 传输观测器。
         */
        private final RpcTransportObserver observer;

        /**
         * Kafka 原生客户端工厂。
         */
        private final KafkaRpcClientFactory clientFactory;

        /**
         * Kafka consumer 配置。
         */
        private final Properties consumerProperties;

        /** 已拉取消息的应用执行策略。 */
        private final KafkaRpcConsumerExecution consumerExecution;

        /** 单个分区处理的最大预算，预留 poll 心跳与提交时间。 */
        private final Duration processingBudget;

        /** 分区有序策略的尾阶段，仅存活分区会保留条目。 */
        private final ConcurrentMap<TopicPartition, CompletableFuture<Void>> partitionTails =
                new ConcurrentHashMap<>();

        /**
         * 消息监听器集合。
         */
        private final Set<KafkaRpcMessageListener> listeners = new CopyOnWriteArraySet<>();

        /**
         * 是否已经启动。
         */
        private final AtomicBoolean started = new AtomicBoolean();

        /**
         * 是否继续运行。
         */
        private final AtomicBoolean running = new AtomicBoolean(true);

        /**
         * consumer 线程。
         */
        private volatile Thread thread;

        /**
         * Kafka consumer。
         */
        private volatile Consumer<String, byte[]> consumer;

        /**
         * 当前关闭事务共享的绝对截止时间。
         */
        private final AtomicReference<KafkaRpcCloseDeadline> closeDeadline = new AtomicReference<>();

        /**
         * consumer 在关闭阶段产生的安全失败。
         */
        private volatile KafkaRpcResourceException shutdownFailure;

        /**
         * 最近一次监听器异常。
         */
        private volatile RuntimeException lastListenerException;

        /**
         * 最近一次 worker 运行异常。
         */
        private volatile RuntimeException lastWorkerException;

        /**
         * 创建 topic worker。
         *
         * @param subscription Kafka topic 与 consumer group 订阅；不可为空。
         * @param settings Kafka RPC 配置；不可为空。
         * @param consumerProperties Kafka consumer 配置；不可为空。
         * @param observer RPC 传输观测器；不可为空。
         * @param clientFactory Kafka 原生客户端工厂；不可为空。
         */
        private TopicWorker(
                final Subscription subscription,
                final KafkaRpcSettings settings,
                final Properties consumerProperties,
                final RpcTransportObserver observer,
                final KafkaRpcConsumerExecution consumerExecution,
                final KafkaRpcClientFactory clientFactory) {
            this.subscription = Objects.requireNonNull(subscription, "subscription");
            this.settings = Objects.requireNonNull(settings, "settings");
            this.consumerProperties = Objects.requireNonNull(consumerProperties, "consumerProperties");
            this.observer = Objects.requireNonNull(observer, "observer");
            this.consumerExecution = Objects.requireNonNull(consumerExecution, "consumerExecution");
            this.processingBudget = processingBudget(consumerProperties, settings.pollTimeout());
            this.clientFactory = Objects.requireNonNull(clientFactory, "clientFactory");
        }

        /**
         * 添加消息监听器。
         *
         * @param listener 消息监听器；不可为空。
         * @return true 表示本次实际新增监听器；线程安全。
         */
        boolean add(final KafkaRpcMessageListener listener) {
            return listeners.add(Objects.requireNonNull(listener, "listener"));
        }

        /**
         * 移除消息监听器。
         *
         * @param listener 消息监听器；不可为空。
         */
        void remove(final KafkaRpcMessageListener listener) {
            listeners.remove(Objects.requireNonNull(listener, "listener"));
        }

        /**
         * 判断是否没有监听器。
         *
         * @return true 表示监听器为空；线程安全。
         */
        boolean empty() {
            return listeners.isEmpty();
        }

        /**
         * 启动 consumer worker。
         */
        void start() {
            if (!started.compareAndSet(false, true)) {
                return;
            }
            try {
                thread = Thread.ofVirtual()
                        .name("zero-rpc-kafka-consumer-"
                                + Integer.toUnsignedString(subscription.hashCode(), 16))
                        .start(this::run);
            } catch (RuntimeException | Error ex) {
                running.set(false);
                throw KafkaRpcResourceException.sanitize("start kafka rpc consumer worker failed", ex);
            }
        }

        /**
         * 请求停止 consumer worker，并传入整个关闭事务共享的截止时间。
         *
         * @param deadline 共享关闭截止时间；不可为空。
         */
        void requestStop(final KafkaRpcCloseDeadline deadline) {
            closeDeadline.compareAndSet(null, Objects.requireNonNull(deadline, "deadline"));
            running.set(false);
            Consumer<String, byte[]> current = consumer;
            if (current != null) {
                try {
                    current.wakeup();
                } catch (RuntimeException | Error ex) {
                    throw KafkaRpcResourceException.sanitize("wakeup kafka rpc consumer failed", ex);
                }
            }
        }

        /**
         * 在共享截止时间内等待 consumer worker 退出。
         *
         * @param deadline 共享关闭截止时间；不可为空。
         */
        void awaitStop(final KafkaRpcCloseDeadline deadline) {
            Thread currentThread = thread;
            if (currentThread != null && currentThread != Thread.currentThread()) {
                try {
                    Objects.requireNonNull(deadline, "deadline").join(currentThread);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    throw KafkaRpcResourceException.create("stop kafka rpc consumer interrupted");
                }
            }
            if (currentThread != null && currentThread.isAlive()) {
                throw KafkaRpcResourceException.create("kafka rpc consumer close deadline exceeded");
            }
            if (shutdownFailure != null) {
                throw shutdownFailure;
            }
        }

        /**
         * 判断 worker 线程是否已经确认终止。
         *
         * @return true 表示 worker 从未成功启动或线程已经退出；线程安全。
         */
        boolean terminated() {
            Thread currentThread = thread;
            return currentThread == null || !currentThread.isAlive();
        }

        private void run() {
            while (running.get()) {
                try {
                    runConsumer();
                } catch (RuntimeException ex) {
                    KafkaRpcResourceException safeFailure = KafkaRpcResourceException.sanitize(
                            "kafka rpc consumer execution failed",
                            ex);
                    lastWorkerException = safeFailure;
                    if (!running.get()) {
                        shutdownFailure = safeFailure;
                        return;
                    }
                    observeRestart(safeFailure);
                    LOGGER.log(System.Logger.Level.ERROR, "kafka rpc consumer stopped unexpectedly");
                    pauseBeforeRestart();
                } finally {
                    consumer = null;
                }
            }
        }

        private void runConsumer() {
            Consumer<String, byte[]> current = createConsumer();
            KafkaRpcResourceException failure = null;
            consumer = current;
            try {
                KafkaRpcConsumerBatch batch = new KafkaRpcConsumerBatch(current);
                current.subscribe(List.of(subscription.topic()), batch);
                while (running.get()) {
                    batch.commitCompleted(processingBudget);
                    ConsumerRecords<String, byte[]> records = current.poll(settings.pollTimeout());
                    if (!records.isEmpty()) {
                        batch.track(records, dispatch(records));
                    }
                }
            } catch (WakeupException ex) {
                if (running.get()) {
                    failure = KafkaRpcResourceException.sanitize("poll kafka rpc consumer failed", ex);
                }
            } catch (RuntimeException | Error ex) {
                failure = KafkaRpcResourceException.sanitize("poll kafka rpc consumer failed", ex);
            } finally {
                consumer = null;
                try {
                    current.close(CloseOptions.timeout(consumerCloseTimeout()));
                } catch (RuntimeException | Error ex) {
                    failure = KafkaRpcResourceException.merge(
                            failure,
                            ex,
                            "close kafka rpc consumer failed");
                }
            }
            if (failure != null) {
                throw failure;
            }
        }

        private Consumer<String, byte[]> createConsumer() {
            try {
                return Objects.requireNonNull(
                        clientFactory.createConsumer(consumerProperties),
                        "consumer");
            } catch (RuntimeException | Error ex) {
                throw KafkaRpcResourceException.sanitize("create kafka rpc consumer failed", ex);
            }
        }

        private Map<TopicPartition, CompletionStage<Void>> dispatch(final ConsumerRecords<String, byte[]> records) {
            if (listeners.isEmpty()) {
                Map<TopicPartition, CompletionStage<Void>> failures = new ConcurrentHashMap<>();
                for (TopicPartition partition : records.partitions()) {
                    failures.put(partition, CompletableFuture.failedFuture(
                            KafkaRpcResourceException.create("no kafka rpc listener")));
                }
                return failures;
            }
            Map<TopicPartition, List<CompletionStage<Void>>> completions = new ConcurrentHashMap<>();
            for (ConsumerRecord<String, byte[]> record : records) {
                KafkaRpcMessage message = new KafkaRpcMessage(record.topic(), record.key(), record.value());
                TopicPartition partition = new TopicPartition(record.topic(), record.partition());
                CompletionStage<Void> completion = consumerExecution.preservePartitionOrder()
                        ? dispatchOrdered(partition, message)
                        : dispatchOnApplicationExecutor(message);
                completions.computeIfAbsent(partition, ignored -> new ArrayList<>()).add(completion);
            }
            Map<TopicPartition, CompletionStage<Void>> result = new ConcurrentHashMap<>();
            completions.forEach((partition, stages) -> result.put(partition, combine(stages)));
            return result;
        }

        /** 把同一分区的下一条消息接在前一条的最终完成之后。 */
        private CompletionStage<Void> dispatchOrdered(
                final TopicPartition partition, final KafkaRpcMessage message) {
            CompletableFuture<Void> current = new CompletableFuture<>();
            CompletableFuture<Void> previous = partitionTails.put(partition, current);
            current.whenComplete((ignored, failure) -> partitionTails.remove(partition, current));
            if (previous == null) {
                bridge(dispatchOnApplicationExecutor(message), current);
            } else {
                bridgeAfter(previous, message, current);
            }
            return current;
        }

        /** 前一条分区消息成功完成后，才投递下一条；失败时保留 offset 并让 worker 重建。 */
        private void bridgeAfter(
                final CompletionStage<Void> previous,
                final KafkaRpcMessage message,
                final CompletableFuture<Void> current) {
            try {
                previous.whenComplete((ignored, priorFailure) -> {
                    if (priorFailure != null) {
                        current.completeExceptionally(priorFailure);
                    } else {
                        bridge(dispatchOnApplicationExecutor(message), current);
                    }
                });
            } catch (RuntimeException | Error failure) {
                current.completeExceptionally(failure);
            }
        }

        /** 将一个消息交给应用执行器，consumer poll 线程只负责提交任务。 */
        private CompletionStage<Void> dispatchOnApplicationExecutor(final KafkaRpcMessage message) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            try {
                consumerExecution.executor().execute(() -> invokeListeners(message, result));
            } catch (RuntimeException | Error failure) {
                listenerFailure(result, failure);
            }
            return result;
        }

        /** 在应用执行器中调用所有监听器并以框架拥有的 future 汇总结果。 */
        private void invokeListeners(final KafkaRpcMessage message, final CompletableFuture<Void> result) {
            List<KafkaRpcMessageListener> currentListeners = List.copyOf(listeners);
            if (currentListeners.isEmpty()) {
                listenerFailure(result, KafkaRpcResourceException.create("no kafka rpc listener"));
                return;
            }
            List<CompletionStage<Void>> stages = new ArrayList<>(currentListeners.size());
            for (KafkaRpcMessageListener listener : currentListeners) {
                try {
                    stages.add(Objects.requireNonNull(listener.onMessageAsync(message), "listener stage"));
                } catch (RuntimeException | Error failure) {
                    listenerFailure(result, failure);
                    return;
                }
            }
            bridge(combine(stages), result);
        }

        /** 汇总 CompletionStage，避免假定第三方实现支持 toCompletableFuture。 */
        private CompletionStage<Void> combine(final List<? extends CompletionStage<Void>> stages) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            if (stages.isEmpty()) {
                result.complete(null);
                return result;
            }
            java.util.concurrent.atomic.AtomicInteger remaining =
                    new java.util.concurrent.atomic.AtomicInteger(stages.size());
            AtomicReference<Throwable> firstFailure = new AtomicReference<>();
            for (CompletionStage<Void> stage : stages) {
                try {
                    stage.whenComplete((ignored, failure) -> {
                        if (failure != null) {
                            firstFailure.compareAndSet(null, failure);
                        }
                        if (remaining.decrementAndGet() == 0) {
                            Throwable currentFailure = firstFailure.get();
                            if (currentFailure == null) {
                                result.complete(null);
                            } else {
                                result.completeExceptionally(currentFailure);
                            }
                        }
                    });
                } catch (RuntimeException | Error failure) {
                    firstFailure.compareAndSet(null, failure);
                    if (remaining.decrementAndGet() == 0) {
                        result.completeExceptionally(firstFailure.get());
                    }
                }
            }
            return result;
        }

        /** 使用 whenComplete 桥接第三方阶段到框架拥有的 CompletableFuture。 */
        private void bridge(final CompletionStage<Void> source, final CompletableFuture<Void> target) {
            try {
                source.whenComplete((ignored, failure) -> {
                    if (failure == null) {
                        target.complete(null);
                    } else {
                        target.completeExceptionally(failure);
                    }
                });
            } catch (RuntimeException | Error failure) {
                target.completeExceptionally(failure);
            }
        }

        /** 记录安全失败并结束本条消息；worker 会放弃未确认 offset 后受控重建。 */
        private void listenerFailure(final CompletableFuture<Void> result, final Throwable failure) {
            lastListenerException = KafkaRpcResourceException.create("kafka rpc listener failed");
            LOGGER.log(System.Logger.Level.ERROR, "kafka rpc listener failed");
            result.completeExceptionally(failure);
        }

        /** 从有效 max.poll.interval.ms 导出处理预算，并预留一次 poll 与提交的时间。 */
        private static Duration processingBudget(final Properties properties, final Duration pollTimeout) {
            Object configured = properties.get(ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG);
            long interval;
            try {
                interval = Long.parseLong(String.valueOf(configured));
            } catch (RuntimeException failure) {
                throw KafkaRpcResourceException.create("kafka rpc max.poll.interval.ms is invalid");
            }
            long pollMillis = Math.max(1L, pollTimeout.toMillis());
            long reserve = Math.max(250L, Math.min(10_000L, pollMillis * 2L));
            if (interval <= reserve) {
                throw KafkaRpcResourceException.create(
                        "kafka rpc max.poll.interval.ms must exceed poll and commit reserve");
            }
            return Duration.ofMillis(interval - reserve);
        }

        private Duration consumerCloseTimeout() {
            KafkaRpcCloseDeadline currentDeadline = closeDeadline.get();
            return currentDeadline == null ? settings.closeTimeout() : currentDeadline.remaining();
        }

        private void pauseBeforeRestart() {
            if (!running.get()) {
                return;
            }
            try {
                Thread.sleep(settings.pollTimeout().toMillis());
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                running.set(false);
                throw KafkaRpcResourceException.create("restart kafka rpc consumer interrupted");
            }
        }

        private void observeRestart(final KafkaRpcResourceException exception) {
            try {
                observer.onEvent(RpcTransportEvent.now(
                        RpcTransportEventType.CONSUMER_RESTARTING,
                        "kafka",
                        "",
                        "",
                        "",
                        "",
                        subscription.topic(),
                        subscription.group(),
                        RpcErrorCode.TRANSPORT_UNAVAILABLE,
                        exception.getMessage(),
                        Map.of()));
            } catch (RuntimeException | Error observerFailure) {
                LOGGER.log(System.Logger.Level.WARNING, "kafka rpc consumer restart observer failed");
            }
        }
    }
}
