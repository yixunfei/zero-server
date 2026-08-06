package group.zn.zero.rpc.descriptor;

import group.zn.zero.rpc.RpcMode;
import group.zn.zero.rpc.codec.RpcCodecBinding;
import group.zn.zero.rpc.common.RpcCallMode;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Objects;

/**
 * RPC 方法描述符。
 *
 * @param serviceInterface 服务接口类型。
 * @param serviceName 服务名。
 * @param serviceVersion 服务版本。
 * @param methodId 服务内稳定方法 ID。
 * @param methodName 诊断方法名。
 * @param topic 传输 topic。
 * @param group 传输消费组。
 * @param javaMethod Java 接口方法。
 * @param callMode 调用模式。
 * @param timeoutMillis 默认超时毫秒数。
 * @param idempotent 是否幂等。
 * @param partitionKey 分区键表达式。
 * @param asyncReturn 是否异步返回。
 * @param resultType 业务结果类型。
 * @param parameterTypes 参数类型列表。
 * @param parameterCodecs 参数 codec 列表。
 * @param resultCodec 结果 codec；Void 结果可为空。
 * @author zn
 */
public record RpcMethodDescriptor(
        Class<?> serviceInterface,
        String serviceName,
        int serviceVersion,
        int methodId,
        String methodName,
        String topic,
        String group,
        Method javaMethod,
        RpcCallMode callMode,
        long timeoutMillis,
        boolean idempotent,
        String partitionKey,
        boolean asyncReturn,
        Class<?> resultType,
        List<Class<?>> parameterTypes,
        List<RpcCodecBinding<?>> parameterCodecs,
        RpcCodecBinding<?> resultCodec,
        String routeServiceName,
        String routeMethodName,
        RpcMode transportMode) {

    /**
     * 创建 RPC 方法描述符，并预计算热路径字段。
     */
    public RpcMethodDescriptor(
            final Class<?> serviceInterface,
            final String serviceName,
            final int serviceVersion,
            final int methodId,
            final String methodName,
            final String topic,
            final String group,
            final Method javaMethod,
            final RpcCallMode callMode,
            final long timeoutMillis,
            final boolean idempotent,
            final String partitionKey,
            final boolean asyncReturn,
            final Class<?> resultType,
            final List<Class<?>> parameterTypes,
            final List<RpcCodecBinding<?>> parameterCodecs,
            final RpcCodecBinding<?> resultCodec) {
        this(
                serviceInterface,
                serviceName,
                serviceVersion,
                methodId,
                methodName,
                topic,
                group,
                javaMethod,
                callMode,
                timeoutMillis,
                idempotent,
                partitionKey,
                asyncReturn,
                resultType,
                parameterTypes,
                parameterCodecs,
                resultCodec,
                routeServiceName(serviceName, serviceVersion),
                Integer.toString(methodId),
                transportMode(callMode));
    }

    /**
     * 创建 RPC 方法描述符。
     *
     * @throws NullPointerException 当必填字段为空时抛出。
     * @throws IllegalArgumentException 当标识、版本或超时非法时抛出。
     */
    public RpcMethodDescriptor {
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(methodName, "methodName");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(javaMethod, "javaMethod");
        Objects.requireNonNull(callMode, "callMode");
        Objects.requireNonNull(partitionKey, "partitionKey");
        Objects.requireNonNull(resultType, "resultType");
        Objects.requireNonNull(parameterTypes, "parameterTypes");
        Objects.requireNonNull(parameterCodecs, "parameterCodecs");
        Objects.requireNonNull(routeServiceName, "routeServiceName");
        Objects.requireNonNull(routeMethodName, "routeMethodName");
        Objects.requireNonNull(transportMode, "transportMode");
        if (serviceName.isBlank()) {
            throw new IllegalArgumentException("serviceName must not be blank");
        }
        if (serviceVersion <= 0) {
            throw new IllegalArgumentException("serviceVersion must be positive");
        }
        if (methodId <= 0) {
            throw new IllegalArgumentException("methodId must be positive");
        }
        if (methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        if (timeoutMillis <= 0L) {
            throw new IllegalArgumentException("timeoutMillis must be positive");
        }
        if (routeServiceName.isBlank()) {
            throw new IllegalArgumentException("routeServiceName must not be blank");
        }
        if (routeMethodName.isBlank()) {
            throw new IllegalArgumentException("routeMethodName must not be blank");
        }
        parameterTypes = List.copyOf(parameterTypes);
        parameterCodecs = List.copyOf(parameterCodecs);
    }

    /**
     * 返回传输层路由服务名。
     *
     * @return 路由服务名；不可为空；线程安全。
     */
    public String routeServiceName() {
        return routeServiceName;
    }

    /**
     * 返回传输层路由方法名。
     *
     * @return 路由方法名；不可为空；线程安全。
     */
    public String routeMethodName() {
        return routeMethodName;
    }

    /**
     * 判断结果是否为 Void。
     *
     * @return true 表示业务结果类型是 Void；线程安全。
     */
    public boolean returnsVoid() {
        return Void.class.equals(resultType);
    }

    private static String routeServiceName(final String serviceName, final int serviceVersion) {
        return Objects.requireNonNull(serviceName, "serviceName") + ":v" + serviceVersion;
    }

    private static RpcMode transportMode(final RpcCallMode mode) {
        return switch (Objects.requireNonNull(mode, "mode")) {
            case REQUEST_RESPONSE -> RpcMode.REQUEST_RESPONSE;
            case ONEWAY -> RpcMode.ONEWAY;
        };
    }
}
