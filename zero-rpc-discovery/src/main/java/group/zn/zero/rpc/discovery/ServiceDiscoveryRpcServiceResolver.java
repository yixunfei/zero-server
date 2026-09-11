package group.zn.zero.rpc.discovery;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.rpc.discovery.RpcDiscoveryMetadata;
import group.zn.zero.rpc.discovery.RpcServiceInstance;
import group.zn.zero.rpc.discovery.RpcServiceQuery;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.rpc.discovery.RpcServiceSelection;
import group.zn.zero.rpc.discovery.RpcServiceSnapshot;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.List;
import java.util.Objects;

/**
 * 基于 `ServiceDiscovery` 的 RPC 服务实例解析器。
 *
 * <p>该实现每次解析时读取服务发现当前快照并转换为 RPC 中立模型，适合作为 Nacos 与 RPC
 * 的保守协作入口。它不改变 `RpcTransport` 调用语义，也不缓存敏感连接信息。
 *
 * @author zn
 */
public final class ServiceDiscoveryRpcServiceResolver implements RpcServiceResolver {

    /**
     * 服务发现实现。
     */
    private final ServiceDiscovery discovery;

    /**
     * round-robin 计数器。
     */
    private final AtomicLong cursor = new AtomicLong();

    /**
     * 创建 RPC 服务实例解析器。
     *
     * @param discovery 服务发现实现；不可为空。
     */
    public ServiceDiscoveryRpcServiceResolver(final ServiceDiscovery discovery) {
        this.discovery = Objects.requireNonNull(discovery, "discovery");
    }

    /**
     * 解析并选择一个 RPC 服务实例。
     *
     * @param query 查询条件；不可为空。
     * @return 选择结果；不可为空；线程安全。
     * @throws ZeroException 无可用实例时抛出，绑定 `RpcErrorCode.SERVICE_NOT_FOUND`。
     */
    @Override
    public RpcServiceSelection resolve(final RpcServiceQuery query) {
        RpcServiceSnapshot current = snapshot(query);
        if (current.instances().isEmpty()) {
            throw ZeroException.of(
                    RpcErrorCode.SERVICE_NOT_FOUND,
                    "rpc service instance not found: " + query.serviceName() + ":v" + query.serviceVersion(),
                    null);
        }
        int index = Math.floorMod(cursor.getAndIncrement(), current.instances().size());
        return new RpcServiceSelection(
                query,
                current.instances().get(index),
                current.instances().size(),
                Instant.now());
    }

    /**
     * 返回当前查询对应的 RPC 服务实例快照。
     *
     * @param query 查询条件；不可为空。
     * @return 快照；不可为空；实例列表不可变、有序、可能为空、线程安全。
     */
    @Override
    public RpcServiceSnapshot snapshot(final RpcServiceQuery query) {
        RpcServiceQuery current = Objects.requireNonNull(query, "query");
        ServiceQuery serviceQuery = new ServiceQuery(
                current.serviceName(),
                current.groupName(),
                current.clusterName().isBlank() ? List.of() : List.of(current.clusterName()),
                current.healthyOnly(),
                true);
        List<RpcServiceInstance> instances = discovery.lookup(serviceQuery).stream()
                .map(NacosRpcMetadataMapper::toRpcServiceInstance)
                .filter(instance -> instance.matches(current))
                .toList();
        return RpcServiceSnapshot.now(current, instances);
    }

    /**
     * 创建 Kafka RPC 默认查询条件。
     *
     * @param serviceName RPC 服务名；不可为空。
     * @param serviceVersion RPC 服务版本；必须为正数。
     * @return 查询条件；不可为空；线程安全。
     */
    public static RpcServiceQuery kafkaQuery(final String serviceName, final int serviceVersion) {
        return RpcServiceQuery.of(serviceName, serviceVersion)
                .withTransport("kafka")
                .withGroupName(RpcDiscoveryMetadata.DEFAULT_GROUP_NAME);
    }
}
