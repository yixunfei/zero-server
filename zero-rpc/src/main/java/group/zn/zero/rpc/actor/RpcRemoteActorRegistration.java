package group.zn.zero.rpc.actor;

import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import java.util.Objects;

/**
 * RPC 远程 Actor handler 注册句柄。
 *
 * @author zn
 */
public final class RpcRemoteActorRegistration implements AutoCloseable {

    /**
     * RPC handler 注册表。
     */
    private final RpcHandlerRegistry handlerRegistry;

    /**
     * RPC 服务名。
     */
    private final String serviceName;

    /**
     * RPC 方法名。
     */
    private final String methodName;

    /**
     * 创建注册句柄。
     *
     * @param handlerRegistry RPC handler 注册表；不可为空。
     * @param serviceName RPC 服务名；不可为空。
     * @param methodName RPC 方法名；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    public RpcRemoteActorRegistration(
            final RpcHandlerRegistry handlerRegistry,
            final String serviceName,
            final String methodName) {
        this.handlerRegistry = Objects.requireNonNull(handlerRegistry, "handlerRegistry");
        this.serviceName = Objects.requireNonNull(serviceName, "serviceName");
        this.methodName = Objects.requireNonNull(methodName, "methodName");
    }

    /**
     * 取消 RPC handler 注册。
     *
     * <p>数据变更：仅移除 handler 注册，不清理本地 Actor 状态；线程安全性由注册表实现声明。
     */
    @Override
    public void close() {
        handlerRegistry.unregister(serviceName, methodName);
    }
}
