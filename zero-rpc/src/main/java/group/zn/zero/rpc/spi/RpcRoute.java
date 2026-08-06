package group.zn.zero.rpc.spi;

/**
 * RPC 路由键。
 *
 * @param serviceName 服务名。
 * @param methodName 方法名。
 * @author zn
 */
public record RpcRoute(String serviceName, String methodName) {

    /**
     * 创建路由键。
     *
     * @throws NullPointerException 当服务名或方法名为空时抛出。
     */
    public RpcRoute {
        java.util.Objects.requireNonNull(serviceName, "serviceName");
        java.util.Objects.requireNonNull(methodName, "methodName");
    }
}
