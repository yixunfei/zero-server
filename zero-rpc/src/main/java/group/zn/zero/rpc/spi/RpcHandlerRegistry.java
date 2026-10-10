package group.zn.zero.rpc.spi;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.error.RpcErrorCode;

/**
 * RPC handler 注册表。
 *
 * @author zn
 */
public interface RpcHandlerRegistry {

    /**
     * 独占注册空闲路由并返回所有权句柄；已有路由必须拒绝，失败不得遗留注册。
     * 线程安全；不支持精确所有权的自定义注册表应拒绝，不能模拟无条件 unregister。
     */
    default RpcHandlerRegistration registerScoped(String serviceName, String methodName, String topic,
            String group, RpcHandler handler) {
        throw ZeroException.of(RpcErrorCode.CONTRACT_INVALID,
                "registry must implement scoped registration for service binding", null);
    }

    /**
     * 注册 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @param handler 处理器；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    void register(String serviceName, String methodName, RpcHandler handler);

    /**
     * 注册带传输元数据的 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @param topic 传输 topic；可为空。
     * @param group 消费组；可为空。
     * @param handler 处理器；不可为空。
     * @throws NullPointerException 当服务名、方法名或处理器为空时抛出。
     */
    default void register(
            final String serviceName,
            final String methodName,
            final String topic,
            final String group,
            final RpcHandler handler) {
        register(serviceName, methodName, handler);
    }

    /**
     * 取消注册 RPC 请求处理器。
     *
     * @param serviceName 路由服务名；不可为空。
     * @param methodName 路由方法名；不可为空。
     * @throws NullPointerException 当服务名或方法名为空时抛出。
     */
    void unregister(String serviceName, String methodName);
}
