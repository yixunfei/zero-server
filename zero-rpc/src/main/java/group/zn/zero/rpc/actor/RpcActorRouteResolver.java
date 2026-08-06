package group.zn.zero.rpc.actor;

import group.zn.zero.actor.ActorMessage;
import group.zn.zero.actor.remote.ActorAddress;
import group.zn.zero.actor.remote.ActorRoute;
import group.zn.zero.actor.remote.ActorRouteResolver;
import group.zn.zero.rpc.discovery.RpcServiceInstance;
import group.zn.zero.rpc.discovery.RpcServiceQuery;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.discovery.RpcServiceSelection;
import java.util.Objects;
import java.util.function.Function;

/**
 * 基于 RPC 服务发现的 Actor 路由解析器。
 *
 * <p>该解析器把 Actor 消息映射为 `RpcServiceQuery`，再把选中的 RPC provider metadata
 * 转换为远程 Actor route。解析器不发送 RPC 请求，也不访问远程 Actor 状态。
 *
 * @author zn
 */
public final class RpcActorRouteResolver implements ActorRouteResolver {

    /**
     * RPC 服务实例解析器。
     */
    private final RpcServiceResolver serviceResolver;

    /**
     * Actor 消息到 RPC 查询的映射函数。
     */
    private final Function<ActorMessage, RpcServiceQuery> queryFactory;

    /**
     * 远程 Actor dispatch 方法名。
     */
    private final String methodName;

    /**
     * 创建 RPC Actor 路由解析器。
     *
     * @param serviceResolver RPC 服务实例解析器；不可为空。
     * @param queryFactory Actor 消息到 RPC 查询的映射函数；不可为空。
     * @param methodName 远程 Actor dispatch 方法名；不可为空白。
     * @throws NullPointerException 当解析器或映射函数为空时抛出。
     * @throws IllegalArgumentException 当方法名为空白时抛出。
     */
    public RpcActorRouteResolver(
            final RpcServiceResolver serviceResolver,
            final Function<ActorMessage, RpcServiceQuery> queryFactory,
            final String methodName) {
        this.serviceResolver = Objects.requireNonNull(serviceResolver, "serviceResolver");
        this.queryFactory = Objects.requireNonNull(queryFactory, "queryFactory");
        if (methodName == null || methodName.isBlank()) {
            throw new IllegalArgumentException("methodName must not be blank");
        }
        this.methodName = methodName;
    }

    /**
     * 解析 Actor 消息为远程 Actor route。
     *
     * @param message Actor 消息；不可为空。
     * @return 远程 Actor route；不可为空；线程安全。
     * @throws group.zn.zero.core.error.ZeroException 当 RPC 服务实例不存在或 metadata 非法时抛出。
     */
    @Override
    public ActorRoute resolve(final ActorMessage message) {
        ActorMessage current = Objects.requireNonNull(message, "message");
        RpcServiceQuery query = Objects.requireNonNull(queryFactory.apply(current), "rpc service query");
        RpcServiceSelection selection = serviceResolver.resolve(query);
        RpcServiceInstance instance = selection.instance();
        ActorAddress address = ActorAddress.remote(
                current.laneKey(),
                instance.serviceName(),
                instance.serviceVersion(),
                instance.instanceId(),
                instance.zone());
        return ActorRoute.remote(
                address,
                methodName,
                instance.transport(),
                instance.requestTopic(),
                instance.consumerGroup(),
                current.laneKey().value(),
                instance.attributes());
    }
}
