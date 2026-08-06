package group.zn.zero.rpc.server;

import group.zn.zero.rpc.descriptor.RpcServiceDescriptor;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import java.util.Objects;

/**
 * 已绑定的 RPC 服务。
 *
 * @author zn
 */
public final class RpcBoundService implements AutoCloseable {

    /**
     * handler 注册表。
     */
    private final RpcHandlerRegistry registry;

    /**
     * 服务描述符。
     */
    private final RpcServiceDescriptor descriptor;

    /**
     * 是否仍处于绑定状态。
     */
    private volatile boolean bound = true;

    /**
     * 创建已绑定 RPC 服务。
     *
     * @param registry handler 注册表；不可为空。
     * @param descriptor 服务描述符；不可为空。
     * @throws NullPointerException 当注册表或描述符为空时抛出。
     */
    public RpcBoundService(final RpcHandlerRegistry registry, final RpcServiceDescriptor descriptor) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
    }

    /**
     * 返回服务描述符。
     *
     * @return 服务描述符；不可为空；线程安全。
     */
    public RpcServiceDescriptor descriptor() {
        return descriptor;
    }

    /**
     * 判断当前绑定是否有效。
     *
     * @return true 表示仍然有效；线程安全。
     */
    public boolean bound() {
        return bound;
    }

    /**
     * 解除当前服务所有方法绑定。
     *
     * @throws RuntimeException 当底层注册表取消注册失败时抛出。
     */
    @Override
    public void close() {
        if (!bound) {
            return;
        }
        descriptor.methods().forEach(method ->
                registry.unregister(method.routeServiceName(), method.routeMethodName()));
        bound = false;
    }
}
