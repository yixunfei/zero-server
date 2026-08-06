package group.zn.zero.rpc.kafka;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.observer.RpcTransportEvent;
import group.zn.zero.rpc.observer.RpcTransportEventType;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka RPC pending 请求表。
 *
 * @author zn
 */
public final class KafkaRpcPendingRequests implements AutoCloseable {

    /**
     * pending 请求表内部日志器。
     */
    private static final System.Logger LOGGER = System.getLogger(KafkaRpcPendingRequests.class.getName());

    /**
     * pending 最大容量。
     */
    private final int capacity;

    /**
     * pending 许可。
     */
    private final Semaphore permits;

    /**
     * correlationId 到 pending future 的映射。
     */
    private final ConcurrentMap<String, PendingRequest> pendingRequests = new ConcurrentHashMap<>();

    /**
     * pending 内部令牌序列。
     */
    private final AtomicLong pendingTokens = new AtomicLong();

    /**
     * 超时时间轮。
     */
    private final KafkaRpcTimeoutWheel timeoutWheel;

    /**
     * RPC 传输观测器。
     */
    private final RpcTransportObserver observer;

    /**
     * 传输名称。
     */
    private final String transportName;

    /**
     * 是否已经关闭。
     */
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * pending 注册次数。
     */
    private final AtomicLong registeredCount = new AtomicLong();

    /**
     * pending 拒绝次数。
     */
    private final AtomicLong rejectedCount = new AtomicLong();

    /**
     * pending 完成次数。
     */
    private final AtomicLong completedCount = new AtomicLong();

    /**
     * pending 失败次数。
     */
    private final AtomicLong failedCount = new AtomicLong();

    /**
     * pending 超时次数。
     */
    private final AtomicLong timeoutCount = new AtomicLong();

    /**
     * 创建 pending 请求表。
     *
     * @param capacity pending 最大容量。
     * @throws IllegalArgumentException 当容量非法时抛出。
     */
    public KafkaRpcPendingRequests(final int capacity) {
        this(capacity, KafkaRpcTimeoutWheel.DEFAULT_TICK_DURATION, KafkaRpcTimeoutWheel.DEFAULT_WHEEL_SIZE);
    }

    /**
     * 创建 pending 请求表。
     *
     * @param capacity pending 最大容量。
     * @param observer RPC 传输观测器；不可为空。
     * @param transportName 传输名称；不可为空。
     * @throws IllegalArgumentException 当容量非法时抛出。
     */
    public KafkaRpcPendingRequests(
            final int capacity,
            final RpcTransportObserver observer,
            final String transportName) {
        this(
                capacity,
                KafkaRpcTimeoutWheel.DEFAULT_TICK_DURATION,
                KafkaRpcTimeoutWheel.DEFAULT_WHEEL_SIZE,
                observer,
                transportName);
    }

    /**
     * 创建 pending 请求表。
     *
     * @param capacity pending 最大容量。
     * @param tickDuration 时间轮 tick 间隔；不可为空。
     * @param wheelSize 时间轮桶数量；必须为正数。
     * @throws NullPointerException 当 tick 间隔为空时抛出。
     * @throws IllegalArgumentException 当容量、tick 间隔或桶数量非法时抛出。
     */
    KafkaRpcPendingRequests(final int capacity, final Duration tickDuration, final int wheelSize) {
        this(capacity, tickDuration, wheelSize, RpcTransportObserver.noop(), "kafka");
    }

    /**
     * 创建 pending 请求表。
     *
     * @param capacity pending 最大容量。
     * @param tickDuration 时间轮 tick 间隔；不可为空。
     * @param wheelSize 时间轮桶数量；必须为正数。
     * @param observer RPC 传输观测器；不可为空。
     * @param transportName 传输名称；不可为空。
     * @throws NullPointerException 当 tick 间隔或 observer 为空时抛出。
     * @throws IllegalArgumentException 当容量、tick 间隔或桶数量非法时抛出。
     */
    KafkaRpcPendingRequests(
            final int capacity,
            final Duration tickDuration,
            final int wheelSize,
            final RpcTransportObserver observer,
            final String transportName) {
        if (capacity <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
        this.capacity = capacity;
        this.permits = new Semaphore(capacity);
        this.observer = Objects.requireNonNull(observer, "observer");
        this.transportName = requireText(transportName, "transportName");
        this.timeoutWheel = new KafkaRpcTimeoutWheel(tickDuration, wheelSize, this::timeout);
    }

    /**
     * 注册等待响应的请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应 future；不可为空；线程安全。
     * @throws NullPointerException 当请求为空时抛出。
     */
    public CompletableFuture<RpcResponse> register(final RpcRequest request) {
        RpcRequest current = Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return rejectedFuture(current, RpcErrorCode.TRANSPORT_UNAVAILABLE, "kafka rpc pending requests is closed");
        }
        long timeoutMillis = Duration.between(Instant.now(), current.timeoutAt()).toMillis();
        if (timeoutMillis <= 0L) {
            return rejectedFuture(current, RpcErrorCode.REQUEST_TIMEOUT, "rpc request already timed out");
        }
        if (!permits.tryAcquire()) {
            return rejectedFuture(current, RpcErrorCode.TRANSPORT_UNAVAILABLE, "kafka rpc pending requests is full");
        }
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        PendingRequest pending = new PendingRequest(
                current.timeoutAt(),
                current.traceId(),
                current.serviceName(),
                current.methodName(),
                future,
                pendingTokens.incrementAndGet());
        PendingRequest previous = pendingRequests.putIfAbsent(current.correlationId(), pending);
        if (previous != null) {
            permits.release();
            return rejectedFuture(current, RpcErrorCode.INVALID_REQUEST,
                    "duplicate rpc correlationId: " + current.correlationId());
        }
        if (closed.get()) {
            if (pendingRequests.remove(current.correlationId(), pending)) {
                finish(pending);
                failedCount.incrementAndGet();
                pending.future().completeExceptionally(ZeroException.of(
                        RpcErrorCode.TRANSPORT_UNAVAILABLE,
                        "kafka rpc pending requests is closed",
                        null));
                observe(RpcTransportEventType.PENDING_FAILED, current, RpcErrorCode.TRANSPORT_UNAVAILABLE,
                        "kafka rpc pending requests is closed");
            }
            return future;
        }
        timeoutWheel.schedule(current.correlationId(), current.timeoutAt(), pending.token());
        registeredCount.incrementAndGet();
        observe(RpcTransportEventType.PENDING_REGISTERED, current, null, "pending request registered");
        return future;
    }

    /**
     * 完成响应。
     *
     * @param response RPC 响应；不可为空。
     * @return true 表示找到并完成 pending 请求；线程安全。
     * @throws NullPointerException 当响应为空时抛出。
     */
    public boolean complete(final RpcResponse response) {
        RpcResponse current = Objects.requireNonNull(response, "response");
        PendingRequest pending = pendingRequests.remove(current.correlationId());
        if (pending == null) {
            return false;
        }
        finish(pending);
        completedCount.incrementAndGet();
        pending.future().complete(current);
        observe(RpcTransportEventType.PENDING_COMPLETED, current, null, "pending request completed");
        return true;
    }

    /**
     * 使指定请求失败。
     *
     * @param correlationId 关联 ID；不可为空。
     * @param errorCode 错误码；不可为空。
     * @param message 错误说明；不可为空。
     * @param cause 原始异常；可为空。
     * @return true 表示找到并完成 pending 请求；线程安全。
     */
    public boolean fail(
            final String correlationId,
            final ErrorCode errorCode,
            final String message,
            final Throwable cause) {
        ErrorCode currentErrorCode = Objects.requireNonNull(errorCode, "errorCode");
        String currentMessage = Objects.requireNonNull(message, "message");
        PendingRequest pending = pendingRequests.remove(Objects.requireNonNull(correlationId, "correlationId"));
        if (pending == null) {
            return false;
        }
        finish(pending);
        failedCount.incrementAndGet();
        pending.future().completeExceptionally(ZeroException.of(
                currentErrorCode,
                currentMessage,
                cause));
        observe(RpcTransportEventType.PENDING_FAILED, correlationId, pending, currentErrorCode, currentMessage);
        return true;
    }

    /**
     * 批量失败所有 pending 请求。
     *
     * @param errorCode 错误码；不可为空。
     * @param message 错误说明；不可为空。
     */
    public void failAll(final ErrorCode errorCode, final String message) {
        Objects.requireNonNull(errorCode, "errorCode");
        Objects.requireNonNull(message, "message");
        pendingRequests.forEach((correlationId, pending) -> {
            if (pendingRequests.remove(correlationId, pending)) {
                finish(pending);
                failedCount.incrementAndGet();
                pending.future().completeExceptionally(ZeroException.of(errorCode, message, null));
                observe(RpcTransportEventType.PENDING_FAILED, correlationId, pending, errorCode, message);
            }
        });
    }

    /**
     * 返回当前 pending 数量。
     *
     * @return 当前 pending 数量；线程安全。
     */
    public int size() {
        return pendingRequests.size();
    }

    /**
     * 返回 pending 最大容量。
     *
     * @return pending 最大容量；线程安全。
     */
    public int capacity() {
        return capacity;
    }

    /**
     * 返回 pending 注册次数。
     *
     * @return 注册次数；线程安全。
     */
    public long registeredCount() {
        return registeredCount.get();
    }

    /**
     * 返回 pending 拒绝次数。
     *
     * @return 拒绝次数；线程安全。
     */
    public long rejectedCount() {
        return rejectedCount.get();
    }

    /**
     * 返回 pending 完成次数。
     *
     * @return 完成次数；线程安全。
     */
    public long completedCount() {
        return completedCount.get();
    }

    /**
     * 返回 pending 失败次数。
     *
     * @return 失败次数；线程安全。
     */
    public long failedCount() {
        return failedCount.get();
    }

    /**
     * 返回 pending 超时次数。
     *
     * @return 超时次数；线程安全。
     */
    public long timeoutCount() {
        return timeoutCount.get();
    }

    /**
     * 关闭 pending 请求表。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        KafkaRpcResourceException failure = null;
        try {
            failAll(RpcErrorCode.TRANSPORT_UNAVAILABLE, "kafka rpc pending requests is closed");
        } catch (RuntimeException | Error closeFailure) {
            failure = KafkaRpcResourceException.sanitize(
                    "fail kafka rpc pending requests during close failed",
                    closeFailure);
        }
        try {
            timeoutWheel.close();
        } catch (RuntimeException | Error closeFailure) {
            failure = KafkaRpcResourceException.merge(
                    failure,
                    closeFailure,
                    "close kafka rpc timeout wheel failed");
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void timeout(final String correlationId, final long token) {
        PendingRequest pending = pendingRequests.get(correlationId);
        if (pending == null || pending.token() != token) {
            return;
        }
        if (pendingRequests.remove(correlationId, pending)) {
            finish(pending);
            timeoutCount.incrementAndGet();
            pending.future().completeExceptionally(ZeroException.of(
                    RpcErrorCode.REQUEST_TIMEOUT,
                    "kafka rpc request timed out",
                    null));
            observe(RpcTransportEventType.PENDING_TIMED_OUT, correlationId, pending,
                    RpcErrorCode.REQUEST_TIMEOUT, "kafka rpc request timed out");
        }
    }

    private void finish(final PendingRequest pending) {
        permits.release();
    }

    private CompletableFuture<RpcResponse> rejectedFuture(
            final RpcRequest request,
            final ErrorCode errorCode,
            final String message) {
        rejectedCount.incrementAndGet();
        CompletableFuture<RpcResponse> future = new CompletableFuture<>();
        future.completeExceptionally(ZeroException.of(errorCode, message, null));
        observe(RpcTransportEventType.PENDING_REJECTED, request, errorCode, message);
        return future;
    }

    private void observe(
            final RpcTransportEventType type,
            final RpcRequest request,
            final ErrorCode errorCode,
            final String message) {
        observe(
                type,
                request.correlationId(),
                request.traceId(),
                request.serviceName(),
                request.methodName(),
                errorCode,
                message);
    }

    private void observe(
            final RpcTransportEventType type,
            final RpcResponse response,
            final ErrorCode errorCode,
            final String message) {
        observe(
                type,
                response.correlationId(),
                response.traceId(),
                "",
                "",
                errorCode,
                message);
    }

    private void observe(
            final RpcTransportEventType type,
            final String correlationId,
            final PendingRequest pending,
            final ErrorCode errorCode,
            final String message) {
        observe(
                type,
                correlationId,
                pending.traceId(),
                pending.serviceName(),
                pending.methodName(),
                errorCode,
                message);
    }

    private void observe(
            final RpcTransportEventType type,
            final String correlationId,
            final String traceId,
            final String serviceName,
            final String methodName,
            final ErrorCode errorCode,
            final String message) {
        try {
            RpcTransportEvent event = RpcTransportEvent.now(
                    type,
                    transportName,
                    correlationId,
                    traceId,
                    serviceName,
                    methodName,
                    "",
                    "",
                    errorCode,
                    message,
                    Map.of());
            observer.onEvent(event);
        } catch (RuntimeException | Error failure) {
            LOGGER.log(System.Logger.Level.WARNING,
                    "kafka rpc pending observer failed: type=" + type);
        }
    }

    private String requireText(final String value, final String name) {
        String current = Objects.requireNonNull(value, name);
        if (current.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return current;
    }

    /**
     * pending 请求。
     *
     * @param timeoutAt 超时时间。
     * @param traceId 链路追踪 ID。
     * @param serviceName 服务名。
     * @param methodName 方法名。
     * @param future 响应 future。
     * @param token pending 内部令牌。
     */
    private record PendingRequest(
            Instant timeoutAt,
            String traceId,
            String serviceName,
            String methodName,
            CompletableFuture<RpcResponse> future,
            long token) {
    }
}
