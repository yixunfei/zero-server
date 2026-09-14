package group.zn.zero.rpc.kafka;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.rpc.observer.RpcTransportSnapshot;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcRoute;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.security.SecurityContext;
import group.zn.zero.security.SecurityMetadataSnapshot;
import group.zn.zero.security.SecurityMetadataVerifier;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka RPC 适配器。
 *
 * @author zn
 */
public final class KafkaRpcAdapter implements RpcTransport, RpcHandlerRegistry, AutoCloseable {

    /**
     * Kafka RPC adapter 内部异常日志器。
     */
    private static final System.Logger LOGGER = System.getLogger(KafkaRpcAdapter.class.getName());

    /**
     * Kafka RPC 配置。
     */
    private final KafkaRpcSettings settings;

    /**
     * Kafka RPC 消息网关。
     */
    private final KafkaRpcMessageGateway gateway;

    /**
     * RPC 传输观测器。
     */
    private final RpcTransportObserver observer;
    /** Receiver-side security metadata verifier; defaults to fail-closed when explicitly enabled. */
    private volatile SecurityMetadataVerifier securityMetadataVerifier = SecurityMetadataVerifier.failClosed();
    /** Whether received RPCs must carry verifiable security metadata. */
    private volatile boolean securityVerificationEnabled;

    /**
     * topic 解析器。
     */
    private final KafkaRpcTopicResolver topicResolver;

    /**
     * envelope 编解码器。
     */
    private final KafkaRpcEnvelopeCodec envelopeCodec = new KafkaRpcEnvelopeCodec();

    /**
     * pending 请求表。
     */
    private final KafkaRpcPendingRequests pendingRequests;

    /**
     * RPC handler 映射表。
     */
    private final ConcurrentMap<RpcRoute, RpcHandler> handlers = new ConcurrentHashMap<>();

    /**
     * request 订阅引用计数。
     */
    private final ConcurrentMap<RequestSubscription, AtomicInteger> requestSubscriptionReferences =
            new ConcurrentHashMap<>();

    /**
     * RPC 路由到 request 订阅的映射。
     */
    private final ConcurrentMap<RpcRoute, RequestSubscription> requestSubscriptionsByRoute =
            new ConcurrentHashMap<>();

    /** handler/request subscription 注册事务与 close 的线性化监视器；不进入 RPC 消息热路径。 */
    private final Object registrationLifecycleMonitor = new Object();

    /**
     * 已订阅 reply topic 集合。
     */
    private final Set<String> replyTopics = ConcurrentHashMap.newKeySet();

    /**
     * 是否已经关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * request/response 发送成功次数。
     */
    private final AtomicLong requestSentCount = new AtomicLong();

    /**
     * oneway 发送成功次数。
     */
    private final AtomicLong onewaySentCount = new AtomicLong();

    /**
     * 收到请求次数。
     */
    private final AtomicLong requestReceivedCount = new AtomicLong();

    /**
     * 消费端拒绝请求次数。
     */
    private final AtomicLong requestRejectedCount = new AtomicLong();

    /**
     * 响应发送成功次数。
     */
    private final AtomicLong responseSentCount = new AtomicLong();

    /**
     * 发送失败次数。
     */
    private final AtomicLong sendFailureCount = new AtomicLong();

    /**
     * consumer 重启尝试次数。
     */
    private final AtomicLong consumerRestartCount = new AtomicLong();

    /**
     * 最近一次发送失败。
     */
    private volatile Throwable lastSendFailure;

    /**
     * request topic 消息监听器。
     */
    private final KafkaRpcMessageListener requestListener = this::onRequestMessage;

    /**
     * reply topic 消息监听器。
     */
    private final KafkaRpcMessageListener replyListener = this::onReplyMessage;

    /**
     * 创建 Kafka RPC 适配器。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @throws NullPointerException 当配置为空时抛出。
     * @throws ZeroException 当 Kafka producer 创建失败时抛出。
     */
    public KafkaRpcAdapter(final KafkaRpcSettings settings) {
        this(settings, RpcTransportObserver.noop());
    }

    /**
     * 创建 Kafka RPC 适配器。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     * @throws ZeroException 当 Kafka producer 创建失败时抛出。
     */
    public KafkaRpcAdapter(final KafkaRpcSettings settings, final RpcTransportObserver observer) {
        this(settings, null, observer);
    }

    /**
     * 创建 Kafka RPC 适配器。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param gateway Kafka RPC 消息网关；可为空，为空时创建 Apache Kafka gateway。
     * @throws NullPointerException 当配置为空时抛出。
     */
    KafkaRpcAdapter(final KafkaRpcSettings settings, final KafkaRpcMessageGateway gateway) {
        this(settings, gateway, RpcTransportObserver.noop());
    }

    /**
     * 创建 Kafka RPC 适配器。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param gateway Kafka RPC 消息网关；可为空，为空时创建 Apache Kafka gateway。
     * @param observer RPC 传输观测器；不可为空。
     * @throws NullPointerException 当配置或 observer 为空时抛出。
     */
    KafkaRpcAdapter(
            final KafkaRpcSettings settings,
            final KafkaRpcMessageGateway gateway,
            final RpcTransportObserver observer) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.observer = Objects.requireNonNull(observer, "observer");
        KafkaRpcMessageGateway currentGateway = gateway == null
                ? new ApacheKafkaRpcMessageGateway(settings, this::observe)
                : gateway;
        this.gateway = currentGateway;
        KafkaRpcPendingRequests currentPendingRequests = null;
        try {
            this.topicResolver = new KafkaRpcTopicResolver(settings.topicPrefix());
            currentPendingRequests = new KafkaRpcPendingRequests(
                    settings.pendingCapacity(),
                    this::observe,
                    name());
            this.pendingRequests = currentPendingRequests;
            ensureReplySubscription(settings.replyTopic());
        } catch (RuntimeException | Error ex) {
            throw rollbackConstruction(currentGateway, currentPendingRequests, ex);
        }
    }

    /** Configures the application-owned receiver verifier. */
    public void securityMetadataVerifier(final SecurityMetadataVerifier verifier) {
        this.securityMetadataVerifier = Objects.requireNonNull(verifier, "verifier");
        this.securityVerificationEnabled = true;
    }

    /**
     * @return 提供者名称；不可为空；线程安全。
     */
    @Override
    public String name() {
        return "kafka";
    }

    /**
     * 发送 request/response 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应阶段；不可为空；线程安全。
     * @throws ZeroException 当请求非法、过期或 adapter 已关闭时抛出。
     */
    @Override
    public CompletionStage<RpcResponse> request(final RpcRequest request) {
        RpcRequest current = validate(request, RpcMode.REQUEST_RESPONSE);
        ensureReplySubscription(current.replyTopic());
        CompletableFuture<RpcResponse> responseFuture = pendingRequests.register(current);
        if (responseFuture.isDone()) {
            return responseFuture;
        }
        sendRequest(current).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                observe(RpcTransportEventType.SEND_FAILED, current, topicResolver.requestTopic(current),
                        current.group(), RpcErrorCode.TRANSPORT_UNAVAILABLE, "send kafka rpc request failed");
                pendingRequests.fail(
                        current.correlationId(),
                        RpcErrorCode.TRANSPORT_UNAVAILABLE,
                        "send kafka rpc request failed",
                        unwrap(throwable));
            } else {
                observe(RpcTransportEventType.REQUEST_SENT, current, topicResolver.requestTopic(current),
                        current.group(), null, "kafka rpc request sent");
            }
        });
        return responseFuture;
    }

    /**
     * 发送 oneway 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成阶段；不可为空；线程安全。
     * @throws ZeroException 当请求非法、过期或 adapter 已关闭时抛出。
     */
    @Override
    public CompletionStage<Void> oneway(final RpcRequest request) {
        RpcRequest current = validate(request, RpcMode.ONEWAY);
        return sendRequest(current).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                observe(RpcTransportEventType.SEND_FAILED, current, topicResolver.requestTopic(current),
                        current.group(), RpcErrorCode.TRANSPORT_UNAVAILABLE, "send kafka rpc oneway failed");
            } else {
                observe(RpcTransportEventType.ONEWAY_SENT, current, topicResolver.requestTopic(current),
                        current.group(), null, "kafka rpc oneway sent");
            }
        });
    }

    /**
     * 注册 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @param handler 处理器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     * @throws ZeroException 当 adapter 已关闭时抛出。
     */
    @Override
    public void register(final String serviceName, final String methodName, final RpcHandler handler) {
        register(serviceName, methodName, "", "", handler);
    }

    /**
     * 注册带 Kafka 元数据的 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @param topic request topic；可为空。
     * @param group 消费组；为空时使用 adapter 默认 consumer group。
     * @param handler 处理器；不可为空。
     * @throws NullPointerException 当服务名、方法名或处理器为空时抛出。
     * @throws ZeroException 当 adapter 已关闭时抛出。
     */
    @Override
    public void register(
            final String serviceName,
            final String methodName,
            final String topic,
            final String group,
            final RpcHandler handler) {
        synchronized (registrationLifecycleMonitor) {
            requireOpen();
            RpcRoute route = new RpcRoute(serviceName, methodName);
            RpcHandler current = Objects.requireNonNull(handler, "handler");
            String requestTopic = topic == null || topic.isBlank() ? topicResolver.requestTopic(serviceName) : topic;
            RequestSubscription subscription = new RequestSubscription(requestTopic, effectiveGroup(group));
            registerLinearized(route, current, subscription);
        }
    }

    /**
     * 取消注册 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @throws NullPointerException 当服务名或方法名为空时抛出。
     */
    @Override
    public void unregister(final String serviceName, final String methodName) {
        synchronized (registrationLifecycleMonitor) {
            if (closed.get()) {
                return;
            }
            RpcRoute route = new RpcRoute(serviceName, methodName);
            RpcHandler previousHandler = handlers.get(route);
            if (previousHandler == null) {
                return;
            }
            RequestSubscription previousSubscription = requestSubscriptionsByRoute.get(route);
            RequestSubscription effectiveSubscription = previousSubscription == null
                    ? new RequestSubscription(topicResolver.requestTopic(route.serviceName()),
                            settings.consumerGroupId())
                    : previousSubscription;
            releaseRequestSubscription(effectiveSubscription);
            handlers.remove(route, previousHandler);
            if (previousSubscription != null) {
                requestSubscriptionsByRoute.remove(route, previousSubscription);
            }
        }
    }

    /**
     * 关闭 Kafka RPC 适配器。
     *
     * @throws ZeroException 当关闭过程中被中断或网关关闭失败时抛出。
     */
    @Override
    public void close() {
        synchronized (registrationLifecycleMonitor) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
        }
        KafkaRpcResourceException failure = closeRequestSubscriptions(null);
        failure = closeReplySubscriptions(failure);
        failure = closePendingRequests(failure);
        failure = closeGateway(failure);
        handlers.clear();
        requestSubscriptionsByRoute.clear();
        requestSubscriptionReferences.clear();
        replyTopics.clear();
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 返回 Kafka RPC 传输只读快照。
     *
     * @return 传输快照；不可为空；线程安全。
     */
    public RpcTransportSnapshot snapshot() {
        return new RpcTransportSnapshot(
                name(),
                closed.get(),
                pendingRequests.size(),
                pendingRequests.capacity(),
                requestSentCount.get(),
                onewaySentCount.get(),
                requestReceivedCount.get(),
                requestRejectedCount.get(),
                responseSentCount.get(),
                sendFailureCount.get(),
                pendingRequests.rejectedCount(),
                pendingRequests.timeoutCount(),
                consumerRestartCount.get(),
                Map.of(
                        "replyTopicCount", String.valueOf(replyTopics.size()),
                        "requestSubscriptionCount", String.valueOf(requestSubscriptionReferences.size()),
                        "pendingRegisteredCount", String.valueOf(pendingRequests.registeredCount()),
                        "pendingCompletedCount", String.valueOf(pendingRequests.completedCount()),
                        "pendingFailedCount", String.valueOf(pendingRequests.failedCount())));
    }

    private CompletionStage<Void> sendRequest(final RpcRequest request) {
        KafkaRpcMessage message = new KafkaRpcMessage(
                topicResolver.requestTopic(request),
                request.partitionKey().isBlank() ? request.correlationId() : request.partitionKey(),
                envelopeCodec.encodeRequest(request));
        return gateway.send(message);
    }

    private void onReplyMessage(final KafkaRpcMessage message) {
        KafkaRpcEnvelope envelope = envelopeCodec.decode(message.value());
        if (envelope.kind() == KafkaRpcEnvelopeKind.RESPONSE) {
            pendingRequests.complete(envelope.response());
        }
    }

    private void onRequestMessage(final KafkaRpcMessage message) {
        KafkaRpcEnvelope envelope = envelopeCodec.decode(message.value());
        if (envelope.kind() != KafkaRpcEnvelopeKind.REQUEST) {
            return;
        }
        RpcRequest request = envelope.request();
        observe(RpcTransportEventType.REQUEST_RECEIVED, request, message.topic(), request.group(),
                null, "kafka rpc request received");
        if (request.timeoutAt().isBefore(Instant.now())) {
            observe(RpcTransportEventType.REQUEST_REJECTED, request, message.topic(), request.group(),
                    RpcErrorCode.REQUEST_TIMEOUT, "kafka rpc request already timed out");
            reject(request, RpcErrorCode.REQUEST_TIMEOUT, "kafka rpc request already timed out");
            return;
        }
        RpcHandler handler = handlers.get(new RpcRoute(request.serviceName(), request.methodName()));
        if (handler == null) {
            observe(RpcTransportEventType.REQUEST_REJECTED, request, message.topic(), request.group(),
                    RpcErrorCode.SERVICE_NOT_FOUND, "rpc handler not found");
            reject(request, RpcErrorCode.SERVICE_NOT_FOUND,
                    "rpc handler not found: " + request.serviceName() + "#" + request.methodName());
            return;
        }
        if (!securityVerificationEnabled) {
            handle(request, handler);
            return;
        }
        if (request.securityMetadata() == null) {
            reject(request, RpcErrorCode.INVALID_REQUEST, "rpc security metadata is required");
            return;
        }
        CompletionStage<SecurityContext> verified;
        try {
            verified = securityMetadataVerifier.verify(request.securityMetadata(), Instant.now());
        } catch (RuntimeException exception) {
            reject(request, RpcErrorCode.INVALID_REQUEST, "rpc security metadata rejected");
            return;
        }
        if (verified == null) {
            reject(request, RpcErrorCode.INVALID_REQUEST, "rpc security metadata rejected");
            return;
        }
        verified.whenComplete((securityContext, failure) -> {
            if (failure != null || securityContext == null || securityContext.expired(Instant.now())) {
                reject(request, RpcErrorCode.INVALID_REQUEST, "rpc security metadata rejected");
                return;
            }
            group.zn.zero.security.SecurityContextBridge.with(securityContext, () -> handle(request, handler));
        });
    }

    private void handle(final RpcRequest request, final RpcHandler handler) {
        CompletionStage<RpcResponse> stage;
        try {
            observe(RpcTransportEventType.HANDLER_STARTED, request, topicResolver.requestTopic(request),
                    request.group(), null, "kafka rpc handler started");
            stage = Objects.requireNonNull(handler.handle(request), "handlerStage");
        } catch (RuntimeException ex) {
            observe(RpcTransportEventType.HANDLER_FAILED, request, topicResolver.requestTopic(request),
                    request.group(), RpcErrorCode.HANDLER_FAILED, messageOf(ex));
            reject(request, RpcErrorCode.HANDLER_FAILED, messageOf(ex));
            return;
        }
        stage.whenComplete((response, throwable) -> {
            if (throwable != null) {
                observe(RpcTransportEventType.HANDLER_FAILED, request, topicResolver.requestTopic(request),
                        request.group(), RpcErrorCode.HANDLER_FAILED, messageOf(unwrap(throwable)));
                reject(request, RpcErrorCode.HANDLER_FAILED, messageOf(unwrap(throwable)));
                return;
            }
            observe(RpcTransportEventType.HANDLER_SUCCEEDED, request, topicResolver.requestTopic(request),
                    request.group(), null, "kafka rpc handler succeeded");
            if (request.mode() == RpcMode.ONEWAY) {
                return;
            }
            RpcResponse current = response == null
                    ? errorResponse(request, RpcErrorCode.HANDLER_FAILED, "rpc handler returned null response")
                    : response;
            sendResponse(request.replyTopic(), current);
        });
    }

    private void reject(final RpcRequest request, final ErrorCode errorCode, final String message) {
        if (request.mode() == RpcMode.ONEWAY) {
            return;
        }
        sendResponse(request.replyTopic(), errorResponse(request, errorCode, message));
    }

    private void sendResponse(final String replyTopic, final RpcResponse response) {
        KafkaRpcMessage message = new KafkaRpcMessage(
                replyTopic,
                response.correlationId(),
                envelopeCodec.encodeResponse(response));
        gateway.send(message).whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                lastSendFailure = unwrap(throwable);
                observe(RpcTransportEventType.SEND_FAILED, response, replyTopic, "",
                        RpcErrorCode.TRANSPORT_UNAVAILABLE, "send kafka rpc response failed");
                LOGGER.log(System.Logger.Level.ERROR,
                        "send kafka rpc response failed: replyTopic=" + replyTopic,
                        lastSendFailure);
            } else {
                observe(RpcTransportEventType.RESPONSE_SENT, response, replyTopic, "",
                        null, "kafka rpc response sent");
            }
        });
    }

    private RpcResponse errorResponse(
            final RpcRequest request,
            final ErrorCode errorCode,
            final String message) {
        return new RpcResponse(
                request.correlationId(),
                request.traceId(),
                new KafkaRpcWireErrorCode(errorCode.category(), errorCode.code(), message),
                message,
                new byte[0]);
    }

    private RpcRequest validate(final RpcRequest request, final RpcMode expectedMode) {
        requireOpen();
        RpcRequest current = Objects.requireNonNull(request, "request");
        if (current.mode() != expectedMode) {
            throw ZeroException.of(
                    RpcErrorCode.INVALID_REQUEST,
                    "rpc mode mismatch: " + current.mode(),
                    null);
        }
        if (current.timeoutAt().isBefore(Instant.now())) {
            throw ZeroException.of(
                    RpcErrorCode.REQUEST_TIMEOUT,
                    "rpc request already timed out: " + current.correlationId(),
                    null);
        }
        return current;
    }

    private void requireOpen() {
        if (closed.get()) {
            throw ZeroException.of(
                    RpcErrorCode.TRANSPORT_UNAVAILABLE,
                    "kafka rpc adapter is closed",
                    null);
        }
    }

    private void ensureReplySubscription(final String replyTopic) {
        subscribeReplyTopic(gateway, replyTopic);
    }

    private void subscribeReplyTopic(
            final KafkaRpcMessageGateway targetGateway,
            final String replyTopic) {
        String currentTopic = Objects.requireNonNull(replyTopic, "replyTopic");
        if (!replyTopics.add(currentTopic)) {
            return;
        }
        try {
            targetGateway.subscribe(currentTopic, replyListener);
            requireOpen();
        } catch (RuntimeException | Error ex) {
            replyTopics.remove(currentTopic);
            KafkaRpcResourceException failure = KafkaRpcResourceException.sanitize(
                    "subscribe kafka rpc reply topic failed",
                    ex);
            try {
                targetGateway.unsubscribe(currentTopic, replyListener);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        cleanupFailure,
                        "rollback kafka rpc reply subscription failed");
            }
            throw failure;
        }
    }

    private KafkaRpcResourceException rollbackConstruction(
            final KafkaRpcMessageGateway currentGateway,
            final KafkaRpcPendingRequests currentPendingRequests,
            final Throwable failure) {
        KafkaRpcResourceException primary = KafkaRpcResourceException.sanitize(
                "initialize kafka rpc adapter failed",
                failure);
        if (currentPendingRequests != null) {
            try {
                currentPendingRequests.close();
            } catch (RuntimeException | Error cleanupFailure) {
                primary = KafkaRpcResourceException.merge(
                        primary,
                        cleanupFailure,
                        "rollback kafka rpc pending requests failed");
            }
        }
        try {
            currentGateway.close();
        } catch (RuntimeException | Error cleanupFailure) {
            primary = KafkaRpcResourceException.merge(
                    primary,
                    cleanupFailure,
                    "rollback kafka rpc gateway failed");
        }
        return primary;
    }

    private KafkaRpcResourceException closeRequestSubscriptions(
            final KafkaRpcResourceException primary) {
        KafkaRpcResourceException failure = primary;
        for (RequestSubscription subscription : requestSubscriptionReferences.keySet()) {
            try {
                gateway.unsubscribe(subscription.topic(), subscription.group(), requestListener);
            } catch (RuntimeException | Error ex) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        ex,
                        "close kafka rpc request subscription failed");
            }
        }
        return failure;
    }

    private KafkaRpcResourceException closeReplySubscriptions(
            final KafkaRpcResourceException primary) {
        KafkaRpcResourceException failure = primary;
        for (String topic : replyTopics) {
            try {
                gateway.unsubscribe(topic, replyListener);
            } catch (RuntimeException | Error ex) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        ex,
                        "close kafka rpc reply subscription failed");
            }
        }
        return failure;
    }

    private KafkaRpcResourceException closePendingRequests(
            final KafkaRpcResourceException primary) {
        try {
            pendingRequests.close();
            return primary;
        } catch (RuntimeException | Error ex) {
            return KafkaRpcResourceException.merge(
                    primary,
                    ex,
                    "close kafka rpc pending requests failed");
        }
    }

    private KafkaRpcResourceException closeGateway(final KafkaRpcResourceException primary) {
        try {
            gateway.close();
            return primary;
        } catch (RuntimeException | Error ex) {
            return KafkaRpcResourceException.merge(primary, ex, "close kafka rpc gateway failed");
        }
    }

    private void registerLinearized(
            final RpcRoute route,
            final RpcHandler handler,
            final RequestSubscription subscription) {
        RpcHandler previousHandler = handlers.put(route, handler);
        RequestSubscription previousSubscription = requestSubscriptionsByRoute.put(route, subscription);
        if (subscription.equals(previousSubscription)) {
            return;
        }
        try {
            retainRequestSubscription(subscription);
        } catch (RuntimeException | Error failure) {
            restoreRegistration(route, handler, subscription, previousHandler, previousSubscription);
            throw KafkaRpcResourceException.sanitize(
                    "register kafka rpc request subscription failed",
                    failure);
        }
        if (previousSubscription == null) {
            return;
        }
        try {
            releaseRequestSubscription(previousSubscription);
        } catch (RuntimeException | Error failure) {
            restoreRegistration(route, handler, subscription, previousHandler, previousSubscription);
            KafkaRpcResourceException primary = KafkaRpcResourceException.sanitize(
                    "replace kafka rpc request subscription failed",
                    failure);
            try {
                releaseRequestSubscription(subscription);
            } catch (RuntimeException | Error cleanupFailure) {
                primary = KafkaRpcResourceException.merge(
                        primary,
                        cleanupFailure,
                        "rollback kafka rpc request subscription replacement failed");
            }
            throw primary;
        }
    }

    private void restoreRegistration(
            final RpcRoute route,
            final RpcHandler currentHandler,
            final RequestSubscription currentSubscription,
            final RpcHandler previousHandler,
            final RequestSubscription previousSubscription) {
        if (previousHandler == null) {
            handlers.remove(route, currentHandler);
        } else {
            handlers.put(route, previousHandler);
        }
        if (previousSubscription == null) {
            requestSubscriptionsByRoute.remove(route, currentSubscription);
        } else {
            requestSubscriptionsByRoute.put(route, previousSubscription);
        }
    }

    private void retainRequestSubscription(final RequestSubscription subscription) {
        requestSubscriptionReferences.compute(subscription, (key, counter) -> {
            if (counter != null) {
                counter.incrementAndGet();
                return counter;
            }
            subscribeRequestTopic(key);
            return new AtomicInteger(1);
        });
    }

    private void releaseRequestSubscription(final RequestSubscription subscription) {
        AtomicInteger counter = requestSubscriptionReferences.get(subscription);
        if (counter == null) {
            return;
        }
        requestSubscriptionReferences.computeIfPresent(subscription, (key, current) -> {
            if (current.get() > 1) {
                current.decrementAndGet();
                return current;
            }
            try {
                gateway.unsubscribe(key.topic(), key.group(), requestListener);
                return null;
            } catch (RuntimeException | Error ex) {
                throw KafkaRpcResourceException.sanitize(
                        "unsubscribe kafka rpc request topic failed",
                        ex);
            }
        });
    }

    private void subscribeRequestTopic(final RequestSubscription subscription) {
        try {
            gateway.subscribe(subscription.topic(), subscription.group(), requestListener);
        } catch (RuntimeException | Error ex) {
            KafkaRpcResourceException failure = KafkaRpcResourceException.sanitize(
                    "subscribe kafka rpc request topic failed",
                    ex);
            try {
                gateway.unsubscribe(subscription.topic(), subscription.group(), requestListener);
            } catch (RuntimeException | Error cleanupFailure) {
                failure = KafkaRpcResourceException.merge(
                        failure,
                        cleanupFailure,
                        "rollback kafka rpc request subscription failed");
            }
            throw failure;
        }
    }

    private String effectiveGroup(final String group) {
        return group == null || group.isBlank() ? settings.consumerGroupId() : group;
    }

    /**
     * request topic + consumer group 订阅键。
     *
     * @param topic request topic。
     * @param group consumer group。
     */
    private record RequestSubscription(String topic, String group) {

        /**
         * 创建 request 订阅键。
         */
        private RequestSubscription {
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(group, "group");
        }
    }

    private Throwable unwrap(final Throwable throwable) {
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    private String messageOf(final Throwable throwable) {
        return throwable == null || throwable.getMessage() == null
                ? SystemErrorCode.SYSTEM_ERROR.message()
                : throwable.getMessage();
    }

    private void observe(final RpcTransportEvent event) {
        RpcTransportEvent current = Objects.requireNonNull(event, "event");
        switch (current.type()) {
            case REQUEST_SENT -> requestSentCount.incrementAndGet();
            case ONEWAY_SENT -> onewaySentCount.incrementAndGet();
            case REQUEST_RECEIVED -> requestReceivedCount.incrementAndGet();
            case REQUEST_REJECTED -> requestRejectedCount.incrementAndGet();
            case RESPONSE_SENT -> responseSentCount.incrementAndGet();
            case SEND_FAILED -> sendFailureCount.incrementAndGet();
            case CONSUMER_RESTARTING -> consumerRestartCount.incrementAndGet();
        }
        try {
            observer.onEvent(current);
        } catch (RuntimeException | Error observerFailure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "kafka rpc observer failed: type=" + current.type());
        }
    }

    private void observe(
            final RpcTransportEventType type,
            final RpcRequest request,
            final String topic,
            final String group,
            final ErrorCode errorCode,
            final String message) {
        observe(RpcTransportEvent.now(
                type,
                name(),
                request.correlationId(),
                request.traceId(),
                request.serviceName(),
                request.methodName(),
                topic,
                group,
                errorCode,
                message,
                Map.of()));
    }

    private void observe(
            final RpcTransportEventType type,
            final RpcResponse response,
            final String topic,
            final String group,
            final ErrorCode errorCode,
            final String message) {
        observe(RpcTransportEvent.now(
                type,
                name(),
                response.correlationId(),
                response.traceId(),
                "",
                "",
                topic,
                group,
                errorCode,
                message,
                Map.of()));
    }
}
