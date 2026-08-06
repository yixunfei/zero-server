package group.zn.zero.rpc.discovery;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.rpc.error.RpcErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 基于本地不可变快照的 RPC round-robin 服务解析器。
 *
 * <p>该实现用于本地测试、服务发现 adapter 桥接和最小生产路由。它不创建线程，不直接订阅注册中心，
 * 调用方需要在服务发现快照变化时显式替换实例列表。
 *
 * @author zn
 */
public final class RoundRobinRpcServiceResolver implements RpcServiceResolver {

    /**
     * 当前实例快照。
     */
    private final AtomicReference<List<RpcServiceInstance>> instances = new AtomicReference<>(List.of());

    /**
     * round-robin 计数器。
     */
    private final AtomicLong cursor = new AtomicLong();

    /**
     * 创建空 resolver。
     */
    public RoundRobinRpcServiceResolver() {
    }

    /**
     * 创建带初始实例列表的 resolver。
     *
     * @param instances 初始实例列表；不可为空。
     */
    public RoundRobinRpcServiceResolver(final List<RpcServiceInstance> instances) {
        replaceInstances(instances);
    }

    /**
     * 替换全部实例快照。
     *
     * @param newInstances 新实例列表；不可为空。
     */
    public void replaceInstances(final List<RpcServiceInstance> newInstances) {
        List<RpcServiceInstance> copied = new ArrayList<>(Objects.requireNonNull(newInstances, "newInstances"));
        copied.sort(Comparator.comparing(RpcServiceInstance::instanceId));
        instances.set(List.copyOf(copied));
    }

    /**
     * 追加或替换指定查询范围内的实例快照。
     *
     * @param snapshot 服务实例快照；不可为空。
     */
    public void replaceSnapshot(final RpcServiceSnapshot snapshot) {
        RpcServiceSnapshot current = Objects.requireNonNull(snapshot, "snapshot");
        List<RpcServiceInstance> copied = new ArrayList<>(instances.get().stream()
                .filter(instance -> !sameScope(instance, current.query()))
                .toList());
        copied.addAll(current.instances());
        copied.sort(Comparator.comparing(RpcServiceInstance::instanceId));
        instances.set(List.copyOf(copied));
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
     * 返回当前查询对应的实例快照。
     *
     * @param query 查询条件；不可为空。
     * @return 快照；不可为空；实例列表不可变、有序、可能为空、线程安全。
     */
    @Override
    public RpcServiceSnapshot snapshot(final RpcServiceQuery query) {
        RpcServiceQuery current = Objects.requireNonNull(query, "query");
        List<RpcServiceInstance> matched = instances.get().stream()
                .filter(instance -> instance.matches(current))
                .toList();
        return RpcServiceSnapshot.now(current, matched);
    }

    private boolean sameScope(final RpcServiceInstance instance, final RpcServiceQuery query) {
        if (!instance.serviceName().equals(query.serviceName()) || instance.serviceVersion() != query.serviceVersion()) {
            return false;
        }
        if (!query.transport().isBlank() && !instance.transport().equals(query.transport())) {
            return false;
        }
        if (!instance.groupName().equals(query.groupName())) {
            return false;
        }
        return query.clusterName().isBlank() || instance.clusterName().equals(query.clusterName());
    }
}
