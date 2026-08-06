package group.zn.zero.rpc.descriptor;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.codec.RpcCodecBinding;
import group.zn.zero.rpc.codec.RpcCodecRegistry;
import group.zn.zero.rpc.common.RpcMethod;
import group.zn.zero.rpc.common.RpcResult;
import group.zn.zero.rpc.common.RpcService;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.lang.reflect.Method;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletionStage;

/**
 * RPC common 接口描述符解析器。
 *
 * @author zn
 */
public final class RpcServiceIntrospector {

    private RpcServiceIntrospector() {
    }

    /**
     * 解析 RPC common 接口。
     *
     * @param serviceInterface 服务接口类型；不可为空。
     * @param codecRegistry codec 注册表；不可为空。
     * @return 服务描述符；不可为空；线程安全。
     * @throws ZeroException 当契约非法或 codec 缺失时抛出。
     */
    public static RpcServiceDescriptor describe(
            final Class<?> serviceInterface,
            final RpcCodecRegistry codecRegistry) {
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(codecRegistry, "codecRegistry");
        RpcService service = serviceInterface.getAnnotation(RpcService.class);
        if (!serviceInterface.isInterface() || service == null) {
            throw contractError("rpc service must be an interface with @RpcService: " + serviceInterface.getName());
        }
        if (service.name().isBlank()) {
            throw contractError("rpc service name must not be blank: " + serviceInterface.getName());
        }
        if (service.version() <= 0) {
            throw contractError("rpc service version must be positive: " + serviceInterface.getName());
        }

        Set<Integer> methodIds = new HashSet<>();
        List<RpcMethodDescriptor> methods = new ArrayList<>();
        for (Method method : serviceInterface.getMethods()) {
            RpcMethod annotation = method.getAnnotation(RpcMethod.class);
            if (annotation == null) {
                continue;
            }
            if (!methodIds.add(annotation.id())) {
                throw contractError("duplicate rpc method id: " + annotation.id());
            }
            methods.add(describeMethod(serviceInterface, service, method, annotation, codecRegistry));
        }
        if (methods.isEmpty()) {
            throw contractError("rpc service has no @RpcMethod: " + serviceInterface.getName());
        }
        return new RpcServiceDescriptor(serviceInterface, service.name(), service.version(),
                service.topic(), service.group(), methods);
    }

    private static RpcMethodDescriptor describeMethod(
            final Class<?> serviceInterface,
            final RpcService service,
            final Method method,
            final RpcMethod annotation,
            final RpcCodecRegistry codecRegistry) {
        if (annotation.id() <= 0) {
            throw contractError("rpc method id must be positive: " + method.getName());
        }
        if (annotation.timeoutMillis() <= 0L) {
            throw contractError("rpc method timeoutMillis must be positive: " + method.getName());
        }
        ReturnType returnType = parseReturnType(method);
        Class<?>[] rawParameterTypes = method.getParameterTypes();
        List<Class<?>> parameterTypes = new ArrayList<>(rawParameterTypes.length);
        List<RpcCodecBinding<?>> parameterCodecs = new ArrayList<>(rawParameterTypes.length);
        for (Class<?> parameterType : rawParameterTypes) {
            parameterTypes.add(parameterType);
            parameterCodecs.add(codecRegistry.require(parameterType));
        }
        RpcCodecBinding<?> resultCodec = Void.class.equals(returnType.resultType())
                ? null
                : codecRegistry.require(returnType.resultType());
        String methodName = annotation.name().isBlank() ? method.getName() : annotation.name();
        return new RpcMethodDescriptor(
                serviceInterface,
                service.name(),
                service.version(),
                annotation.id(),
                methodName,
                service.topic(),
                service.group(),
                method,
                annotation.mode(),
                annotation.timeoutMillis(),
                annotation.idempotent(),
                annotation.partitionKey(),
                returnType.async(),
                returnType.resultType(),
                parameterTypes,
                parameterCodecs,
                resultCodec);
    }

    private static ReturnType parseReturnType(final Method method) {
        Type genericReturnType = method.getGenericReturnType();
        if (genericReturnType instanceof ParameterizedType parameterized
                && CompletionStage.class.equals(rawClass(parameterized.getRawType()))) {
            Type[] arguments = parameterized.getActualTypeArguments();
            if (arguments.length != 1) {
                throw contractError("CompletionStage return must have one type argument: " + method.getName());
            }
            return new ReturnType(resultType(arguments[0], method), true);
        }
        return new ReturnType(resultType(genericReturnType, method), false);
    }

    private static Class<?> resultType(final Type type, final Method method) {
        if (!(type instanceof ParameterizedType parameterized)
                || !RpcResult.class.equals(rawClass(parameterized.getRawType()))) {
            throw contractError("rpc method must return RpcResult<T> or CompletionStage<RpcResult<T>>: "
                    + method.getName());
        }
        Type[] arguments = parameterized.getActualTypeArguments();
        if (arguments.length != 1 || !(arguments[0] instanceof Class<?> resultClass)) {
            throw contractError("rpc result type must be a concrete class: " + method.getName());
        }
        return resultClass;
    }

    private static Class<?> rawClass(final Type type) {
        return type instanceof Class<?> clazz ? clazz : null;
    }

    private static ZeroException contractError(final String message) {
        return ZeroException.of(RpcErrorCode.CONTRACT_INVALID, message, null);
    }

    /**
     * 方法返回类型解析结果。
     *
     * @param resultType 业务结果类型。
     * @param async 是否异步返回。
     */
    private record ReturnType(Class<?> resultType, boolean async) {
    }
}
