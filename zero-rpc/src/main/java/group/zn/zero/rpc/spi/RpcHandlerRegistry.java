package group.zn.zero.rpc.spi;

/**
 * RPC handler 注册表。
 *
 * @author zn
 */
public interface RpcHandlerRegistry {

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
