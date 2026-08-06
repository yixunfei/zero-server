package group.zn.zero.rpc.server;

import group.zn.zero.core.error.ErrorCode;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.codec.RpcPayloadCodec;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.descriptor.RpcMethodDescriptor;
import group.zn.zero.rpc.descriptor.RpcServiceDescriptor;
import group.zn.zero.rpc.descriptor.RpcServiceIntrospector;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * RPC common 接口服务端绑定器。
 *
 * @author zn
 */
public final class RpcServiceBinder {

    /**
     * handler 注册表。
     */
    private final RpcHandlerRegistry registry;

    /**
     * codec 注册表。
     */
    private final RpcCodecRegistry codecRegistry;

    /**
     * 鎺ュ彛绫诲瀷鍒版湇鍔℃弿杩扮鐨勫疄渚嬬骇缂撳瓨銆?     */
    private final ConcurrentMap<Class<?>, RpcServiceDescriptor> descriptors = new ConcurrentHashMap<>();

    /**
     * 创建 RPC common 接口服务端绑定器。
     *
     * @param registry handler 注册表；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcServiceBinder(final RpcHandlerRegistry registry, final RpcCodecRegistry codecRegistry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.codecRegistry = Objects.requireNonNull(codecRegistry, "codecRegistry");
    }

    /**
     * 绑定 RPC common 接口实现。
     *
     * @param serviceInterface 服务接口类型；不可为空。
     * @param implementation 服务实现；不可为空。
     * @param <T> 服务接口类型。
     * @return 已绑定服务；不可为空；线程安全。
     * @throws ZeroException 当接口契约非法、codec 缺失或实现未实现接口时抛出。
     */
    public <T> RpcBoundService bind(final Class<T> serviceInterface, final T implementation) {
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(implementation, "implementation");
        if (!serviceInterface.isInstance(implementation)) {
            throw ZeroException.of(
                    RpcErrorCode.CONTRACT_INVALID,
                    "rpc implementation does not implement interface: " + serviceInterface.getName(),
                    null);
        }
        RpcServiceDescriptor descriptor = describe(serviceInterface);
        for (RpcMethodDescriptor method : descriptor.methods()) {
            registry.register(method.routeServiceName(), method.routeMethodName(), method.topic(), method.group(),
                    handler(implementation, method, implementationMethod(implementation, method)));
        }
        return new RpcBoundService(registry, descriptor);
    }

    private RpcServiceDescriptor describe(final Class<?> serviceInterface) {
        return descriptors.computeIfAbsent(serviceInterface,
                current -> RpcServiceIntrospector.describe(current, codecRegistry));
    }

    private RpcHandler handler(
            final Object implementation,
            final RpcMethodDescriptor descriptor,
            final Method implementationMethod) {
        return request -> {
            Object[] arguments = RpcPayloadCodec.decodeArguments(descriptor, request.payload());
            try {
                Object rawResult = implementationMethod.invoke(implementation, arguments);
                if (descriptor.asyncReturn()) {
                    if (!(rawResult instanceof CompletionStage<?> stage)) {
                        return completedFailure(request, RpcErrorCode.INVOCATION_FAILED,
                                "rpc async method did not return CompletionStage");
                    }
                    return stage.handle((value, throwable) ->
                            throwable == null
                                    ? responseFromResult(request, descriptor, value)
                                    : responseFromThrowable(request, throwable));
                }
                return CompletableFuture.completedFuture(responseFromResult(request, descriptor, rawResult));
            } catch (InvocationTargetException ex) {
                return completed(responseFromThrowable(request, ex.getCause()));
            } catch (RuntimeException | IllegalAccessException ex) {
                return completed(responseFromThrowable(request, ex));
            }
        };
    }

    private Method implementationMethod(final Object implementation, final RpcMethodDescriptor descriptor) {
        try {
            Method method = implementation.getClass().getMethod(
                    descriptor.javaMethod().getName(),
                    descriptor.javaMethod().getParameterTypes());
            method.setAccessible(true);
            return method;
        } catch (NoSuchMethodException ex) {
            throw ZeroException.of(
                    RpcErrorCode.CONTRACT_INVALID,
                    "rpc implementation method not found: " + descriptor.javaMethod().getName(),
                    ex);
        } catch (SecurityException ex) {
            throw ZeroException.of(
                    RpcErrorCode.CONTRACT_INVALID,
                    "rpc implementation method is not accessible: " + descriptor.javaMethod().getName(),
                    ex);
        }
    }

    private RpcResponse responseFromResult(
            final group.zn.zero.rpc.RpcRequest request,
            final RpcMethodDescriptor descriptor,
            final Object rawResult) {
        if (!(rawResult instanceof RpcResult<?> rpcResult)) {
            return errorResponse(request, RpcErrorCode.INVOCATION_FAILED,
                    "rpc method must return RpcResult or CompletionStage<RpcResult>");
        }
        if (!rpcResult.success()) {
            return errorResponse(request, rpcResult.errorCode(), rpcResult.errorMsg());
        }
        byte[] payload = RpcPayloadCodec.encodeResult(descriptor, rpcResult.result());
        return new RpcResponse(request.correlationId(), request.traceId(), SystemErrorCode.OK,
                SystemErrorCode.OK.message(), payload);
    }

    private RpcResponse responseFromThrowable(
            final group.zn.zero.rpc.RpcRequest request,
            final Throwable throwable) {
        Throwable current = unwrap(throwable);
        if (current instanceof ZeroException zeroException) {
            return errorResponse(request, zeroException.errorCode(), zeroException.message());
        }
        String message = current == null || current.getMessage() == null
                ? RpcErrorCode.INVOCATION_FAILED.message()
                : current.getMessage();
        return errorResponse(request, RpcErrorCode.INVOCATION_FAILED, message);
    }

    private CompletionStage<RpcResponse> completedFailure(
            final group.zn.zero.rpc.RpcRequest request,
            final ErrorCode errorCode,
            final String message) {
        return completed(errorResponse(request, errorCode, message));
    }

    private CompletionStage<RpcResponse> completed(final RpcResponse response) {
        return CompletableFuture.completedFuture(response);
    }

    private RpcResponse errorResponse(
            final group.zn.zero.rpc.RpcRequest request,
            final ErrorCode errorCode,
            final String message) {
        ErrorCode current = Objects.requireNonNull(errorCode, "errorCode");
        return new RpcResponse(request.correlationId(), request.traceId(), current, message, new byte[0]);
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
}
