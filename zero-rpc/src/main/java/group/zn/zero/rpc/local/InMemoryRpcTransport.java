package group.zn.zero.rpc.local;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcRoute;
import group.zn.zero.rpc.spi.RpcTransport;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * 本地内存 RPC 传输实现。
 *
 * <p>用于阶段 2B 的本地 smoke 和适配器验证，支持 request/response 和 oneway。
 *
 * @author zn
 */
public final class InMemoryRpcTransport implements RpcTransport, RpcHandlerRegistry {

    /**
     * 请求路由表。
     */
    private final ConcurrentMap<RpcRoute, RpcHandler> requestHandlers = new ConcurrentHashMap<>();

    /**
     * 传输可用状态。
     */
    private volatile boolean available = true;

    /**
     * 返回提供者名称。
     *
     * @return 提供者名称；不可为空；线程安全。
     */
    @Override
    public String name() {
        return "memory";
    }

    /**
     * 处理请求并返回响应。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应阶段；不可为空；线程安全。
     * @throws ZeroException 当请求超时、传输不可用或找不到处理器时抛出。
     */
    @Override
    public CompletionStage<RpcResponse> request(final RpcRequest request) {
        RpcRequest current = validate(request);
        RpcHandler handler = requestHandlers.get(new RpcRoute(current.serviceName(), current.methodName()));
        if (handler == null) {
            return failedFuture(RpcErrorCode.SERVICE_NOT_FOUND,
                    "rpc handler not found: " + current.serviceName() + "#" + current.methodName());
        }
        try {
            CompletionStage<RpcResponse> stage = Objects.requireNonNull(handler.handle(current), "handlerStage");
            return stage.thenApply(response -> validateResponse(current, response));
        } catch (RuntimeException ex) {
            return failedFuture(RpcErrorCode.HANDLER_FAILED, ex.getMessage(), ex);
        }
    }

    /**
     * 发送单向请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成信号；不可为空；线程安全。
     * @throws ZeroException 当请求超时、传输不可用或找不到处理器时抛出。
     */
    @Override
    public CompletionStage<Void> oneway(final RpcRequest request) {
        RpcRequest current = validate(request);
        RpcHandler handler = requestHandlers.get(new RpcRoute(current.serviceName(), current.methodName()));
        if (handler == null) {
            return failedFuture(RpcErrorCode.SERVICE_NOT_FOUND,
                    "rpc handler not found: " + current.serviceName() + "#" + current.methodName());
        }
        try {
            CompletionStage<RpcResponse> stage = Objects.requireNonNull(handler.handle(current), "handlerStage");
            return stage.thenApply(ignored -> null);
        } catch (RuntimeException ex) {
            return failedFuture(RpcErrorCode.HANDLER_FAILED, ex.getMessage(), ex);
        }
    }

    /**
     * 注册请求处理器。
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param handler 处理器；不可为空。
     */
    public void register(final String serviceName, final String methodName, final RpcHandler handler) {
        requestHandlers.put(new RpcRoute(serviceName, methodName), Objects.requireNonNull(handler, "handler"));
    }

    /**
     * 清除请求处理器。
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     */
    public void unregister(final String serviceName, final String methodName) {
        requestHandlers.remove(new RpcRoute(serviceName, methodName));
    }

    /**
     * 设置传输可用状态。
     *
     * @param available 是否可用。
     */
    public void available(final boolean available) {
        this.available = available;
    }

    /**
     * 返回传输是否可用。
     *
     * @return true 表示可用；线程安全。
     */
    public boolean available() {
        return available;
    }

    private RpcRequest validate(final RpcRequest request) {
        RpcRequest current = Objects.requireNonNull(request, "request");
        if (!available) {
            throw ZeroException.of(RpcErrorCode.TRANSPORT_UNAVAILABLE, "rpc transport is unavailable", null);
        }
        Instant timeoutAt = current.timeoutAt();
        if (timeoutAt.isBefore(Instant.now())) {
            throw ZeroException.of(RpcErrorCode.REQUEST_TIMEOUT,
                    "rpc request already timed out: " + current.correlationId(),
                    null);
        }
        return current;
    }

    private RpcResponse validateResponse(final RpcRequest request, final RpcResponse response) {
        RpcResponse current = Objects.requireNonNull(response, "response");
        if (!request.correlationId().equals(current.correlationId())) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR,
                    "rpc response correlationId mismatch: " + current.correlationId(),
                    null);
        }
        if (!request.traceId().equals(current.traceId())) {
            throw ZeroException.of(SystemErrorCode.SYSTEM_ERROR,
                    "rpc response traceId mismatch: " + current.traceId(),
                    null);
        }
        return current;
    }

    private <T> CompletableFuture<T> failedFuture(final group.zn.zero.core.error.ErrorCode errorCode,
                                                  final String message) {
        return failedFuture(errorCode, message, null);
    }

    private <T> CompletableFuture<T> failedFuture(final group.zn.zero.core.error.ErrorCode errorCode,
                                                  final String message,
                                                  final Throwable cause) {
        CompletableFuture<T> future = new CompletableFuture<>();
        future.completeExceptionally(ZeroException.of(errorCode, message, cause));
        return future;
    }
}
