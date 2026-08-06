package group.zn.zero.rpc.descriptor;

import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * RPC 服务描述符。
 *
 * @param serviceInterface 服务接口类型。
 * @param serviceName 服务名。
 * @param serviceVersion 服务版本。
 * @param topic 服务默认 topic。
 * @param group 服务默认 consumer group。
 * @param methods 方法描述符列表。
 * @param methodMap Java 方法映射表。
 * @author zn
 */
public record RpcServiceDescriptor(
        Class<?> serviceInterface,
        String serviceName,
        int serviceVersion,
        String topic,
        String group,
        List<RpcMethodDescriptor> methods,
        Map<Method, RpcMethodDescriptor> methodMap) {

    /**
     * 基于方法列表创建 RPC 服务描述符。
     *
     * @param serviceInterface 服务接口类型；不可为空。
     * @param serviceName 服务名；不可为空。
     * @param serviceVersion 服务版本；必须大于 0。
     * @param methods 方法描述符列表；不可为空。
     */
    public RpcServiceDescriptor(
            final Class<?> serviceInterface,
            final String serviceName,
            final int serviceVersion,
            final String topic,
            final String group,
            final List<RpcMethodDescriptor> methods) {
        this(serviceInterface, serviceName, serviceVersion, topic, group, methods, toMethodMap(methods));
    }

    /**
     * 创建 RPC 服务描述符。
     *
     * @throws NullPointerException 当必填字段为空时抛出。
     * @throws IllegalArgumentException 当服务版本非法时抛出。
     */
    public RpcServiceDescriptor {
        Objects.requireNonNull(serviceInterface, "serviceInterface");
        Objects.requireNonNull(serviceName, "serviceName");
        Objects.requireNonNull(topic, "topic");
        Objects.requireNonNull(group, "group");
        Objects.requireNonNull(methods, "methods");
        Objects.requireNonNull(methodMap, "methodMap");
        if (serviceVersion <= 0) {
            throw new IllegalArgumentException("serviceVersion must be positive");
        }
        methods = List.copyOf(methods);
        methodMap = Map.copyOf(methodMap);
    }

    /**
     * 根据 Java 方法查找 RPC 方法描述符。
     *
     * @param method Java 方法；不可为空。
     * @return 方法描述符；找不到时返回 null；线程安全。
     */
    public RpcMethodDescriptor method(final Method method) {
        return methodMap.get(Objects.requireNonNull(method, "method"));
    }

    private static Map<Method, RpcMethodDescriptor> toMethodMap(final List<RpcMethodDescriptor> methods) {
        Map<Method, RpcMethodDescriptor> result = new LinkedHashMap<>();
        for (RpcMethodDescriptor method : Objects.requireNonNull(methods, "methods")) {
            result.put(method.javaMethod(), method);
        }
        return result;
    }
}
