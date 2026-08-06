package group.zn.zero.rpc.discovery;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * RPC 服务实例不可变快照。
 *
 * @param query 快照对应查询条件。
 * @param instances 服务实例列表；不可变、有序、可能为空、线程安全。
 * @param updatedAt 快照更新时间。
 * @author zn
 */
public record RpcServiceSnapshot(
        RpcServiceQuery query,
        List<RpcServiceInstance> instances,
        Instant updatedAt) {

    /**
     * 创建 RPC 服务实例快照。
     *
     * @throws NullPointerException 当查询条件、实例列表或更新时间为空时抛出。
     */
    public RpcServiceSnapshot {
        Objects.requireNonNull(query, "query");
        instances = Objects.requireNonNull(instances, "instances").stream()
                .filter(instance -> instance.matches(query))
                .sorted(Comparator.comparing(RpcServiceInstance::instanceId))
                .toList();
        Objects.requireNonNull(updatedAt, "updatedAt");
    }

    /**
     * 创建当前时间的快照。
     *
     * @param query 查询条件；不可为空。
     * @param instances 服务实例列表；不可为空。
     * @return 快照；不可为空；线程安全。
     */
    public static RpcServiceSnapshot now(
            final RpcServiceQuery query,
            final List<RpcServiceInstance> instances) {
        return new RpcServiceSnapshot(query, instances, Instant.now());
    }

    /**
     * 返回实例数量。
     *
     * @return 实例数量；线程安全。
     */
    public int size() {
        return instances.size();
    }
}
