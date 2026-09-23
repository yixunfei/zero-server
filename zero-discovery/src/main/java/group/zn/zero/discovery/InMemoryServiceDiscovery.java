package group.zn.zero.discovery;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 本地内存服务发现实现。
 *
 * <p>用于本地开发和测试，不依赖 Nacos。该实现按同一套 `ServiceDiscovery` API 暴露服务注册、
 * 查询、健康状态更新和最终实例快照订阅能力，适合项目体量较小时作为最小服务发现策略。
 *
 * @author zn
 */
public class InMemoryServiceDiscovery extends AbstractLifecycle implements ServiceDiscovery {

    /**
     * 注册表名称。
     */
    private final String registryName;

    /**
     * 服务实例注册表，第一层为服务名，第二层为分组与实例标识组合键。
     */
    private final ConcurrentMap<String, ConcurrentMap<String, ServiceInstance>> services = new ConcurrentHashMap<>();

    /**
     * 全局服务事件监听器。
     */
    private final CopyOnWriteArrayList<ServiceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * 按查询条件注册的快照订阅。
     */
    private final CopyOnWriteArrayList<LocalSubscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * 监听器失败记录。
     */
    private final CopyOnWriteArrayList<ZeroException> listenerFailures = new CopyOnWriteArrayList<>();

    /**
     * 健康状态。
     */
    private volatile boolean healthy = true;

    /**
     * 创建本地内存服务发现实现。
     */
    public InMemoryServiceDiscovery() {
        this("local-memory");
    }

    /**
     * 创建本地内存服务发现实现。
     *
     * @param registryName 注册表名称；不可为空。
     * @throws NullPointerException 当注册表名称为空时抛出。
     */
    public InMemoryServiceDiscovery(final String registryName) {
        this.registryName = Objects.requireNonNull(registryName, "registryName");
    }

    /**
     * 返回注册表名称。
     *
     * @return 注册表名称；不可为空；线程安全。
     */
    public String registryName() {
        return registryName;
    }

    /**
     * 返回是否健康。
     *
     * @return true 表示健康；线程安全。
     */
    public boolean healthy() {
        return healthy;
    }

    /**
     * 返回监听器失败快照。
     *
     * @return 不可变、无序、可能为空、线程安全的失败列表。
     */
    public List<ZeroException> listenerFailures() {
        return List.copyOf(listenerFailures);
    }

    /**
     * 注册服务实例。
     *
     * @param instance 服务实例；不可为空。
     */
    @Override
    public void register(final ServiceInstance instance) {
        ensureAvailable();
        ServiceInstance current = Objects.requireNonNull(instance, "instance");
        services.computeIfAbsent(current.serviceName(), ignored -> new ConcurrentHashMap<>())
                .put(instanceKey(current.groupName(), current.instanceId()), current);
        notifySnapshot(ServiceEventType.REGISTERED, current.serviceName(), current.groupName(), List.of(current.clusterName()));
    }

    /**
     * 注销默认分组中的服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     */
    @Override
    public void unregister(final String serviceName, final String instanceId) {
        unregister(serviceName, ServiceDiscoveryConstants.DEFAULT_GROUP_NAME, instanceId);
    }

    /**
     * 注销服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param groupName 服务分组；不可为空。
     * @param instanceId 实例标识；不可为空。
     */
    @Override
    public void unregister(final String serviceName, final String groupName, final String instanceId) {
        ensureAvailable();
        String checkedServiceName = Objects.requireNonNull(serviceName, "serviceName");
        String checkedGroupName = Objects.requireNonNull(groupName, "groupName");
        ConcurrentMap<String, ServiceInstance> instances = services.get(checkedServiceName);
        if (instances == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "service not found: " + serviceName, null);
        }
        ServiceInstance removed = instances.remove(instanceKey(checkedGroupName, Objects.requireNonNull(instanceId, "instanceId")));
        if (removed == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "instance not found: " + instanceId, null);
        }
        notifySnapshot(ServiceEventType.UNREGISTERED, checkedServiceName, checkedGroupName, List.of(removed.clusterName()));
    }

    /**
     * 更新默认分组中的服务实例健康状态。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @param healthy 是否健康。
     */
    @Override
    public void updateHealth(final String serviceName, final String instanceId, final boolean healthy) {
        updateHealth(serviceName, ServiceDiscoveryConstants.DEFAULT_GROUP_NAME, instanceId, healthy);
    }

    /**
     * 更新服务实例健康状态。
     *
     * @param serviceName 服务名称；不可为空。
     * @param groupName 服务分组；不可为空。
     * @param instanceId 实例标识；不可为空。
     * @param healthy 是否健康。
     */
    @Override
    public void updateHealth(
            final String serviceName,
            final String groupName,
            final String instanceId,
            final boolean healthy) {
        ensureAvailable();
        String checkedServiceName = Objects.requireNonNull(serviceName, "serviceName");
        String checkedGroupName = Objects.requireNonNull(groupName, "groupName");
        ConcurrentMap<String, ServiceInstance> instances = services.get(checkedServiceName);
        if (instances == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "service not found: " + serviceName, null);
        }
        ServiceInstance updated = instances.computeIfPresent(
                instanceKey(checkedGroupName, Objects.requireNonNull(instanceId, "instanceId")),
                (ignored, instance) -> instance.withHealthy(healthy));
        if (updated == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "instance not found: " + instanceId, null);
        }
        notifySnapshot(ServiceEventType.HEALTH_CHANGED, checkedServiceName, checkedGroupName, List.of(updated.clusterName()));
    }

    /**
     * 查询默认分组中的服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @return 服务实例列表；不可为空；可能为空；返回集合不可变；无承诺顺序；线程安全。
     */
    @Override
    public List<ServiceInstance> lookup(final String serviceName) {
        return lookup(ServiceQuery.of(serviceName));
    }

    /**
     * 查询服务实例。
     *
     * @param query 查询条件；不可为空。
     * @return 服务实例列表；不可为空；可能为空；返回集合不可变；无承诺顺序；线程安全。
     */
    @Override
    public List<ServiceInstance> lookup(final ServiceQuery query) {
        ensureAvailable();
        return lookupInternal(Objects.requireNonNull(query, "query"));
    }

    /**
     * 监听服务事件。
     *
     * @param listener 监听器；不可为空。
     */
    @Override
    public void addListener(final ServiceDiscoveryListener listener) {
        listeners.add(Objects.requireNonNull(listener, "listener"));
    }

    /**
     * 订阅服务最终实例快照变更。
     *
     * @param query 查询条件；不可为空。
     * @param listener 监听器；不可为空。
     * @return 订阅句柄；不可为空；线程安全。
     */
    @Override
    public ServiceSubscription subscribe(final ServiceQuery query, final ServiceDiscoveryListener listener) {
        ensureAvailable();
        LocalSubscription subscription = new LocalSubscription(
                Objects.requireNonNull(query, "query"),
                Objects.requireNonNull(listener, "listener"));
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * 返回服务注册快照。
     *
     * @return 不可变、无序、可能为空、线程安全的注册表快照。
     */
    @Override
    public Map<String, List<ServiceInstance>> snapshot() {
        return Map.copyOf(services.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> List.copyOf(entry.getValue().values()))));
    }

    /**
     * 启动时标记健康。
     */
    @Override
    protected void doStart() {
        healthy = true;
    }

    /**
     * 停止时清理订阅并标记不健康。
     */
    @Override
    protected void doStop() {
        subscriptions.clear();
        healthy = false;
    }

    private List<ServiceInstance> lookupInternal(final ServiceQuery query) {
        ConcurrentMap<String, ServiceInstance> instances = services.get(query.serviceName());
        if (instances == null) {
            return List.of();
        }
        return instances.values().stream()
                .filter(instance -> instance.groupName().equals(query.groupName()))
                .filter(instance -> query.clusters().isEmpty() || query.clusters().contains(instance.clusterName()))
                .filter(instance -> !query.healthyOnly() || instance.healthy())
                .toList();
    }

    private void ensureAvailable() {
        if (!running() || !healthy) {
            throw ZeroException.of(DiscoveryErrorCode.REGISTRY_UNAVAILABLE,
                    "service registry is unavailable: " + registryName,
                    null);
        }
    }

    private void notifySnapshot(
            final ServiceEventType type,
            final String serviceName,
            final String groupName,
            final List<String> clusters) {
        ServiceQuery eventQuery = new ServiceQuery(serviceName, groupName, clusters, false, false);
        ServiceEvent event = new ServiceEvent(type, serviceName, groupName, clusters, lookupInternal(eventQuery), Instant.now());
        for (ServiceDiscoveryListener listener : listeners) {
            notifyListener(listener, event);
        }
        for (LocalSubscription subscription : subscriptions) {
            subscription.notifyIfMatches(type, serviceName, groupName);
        }
    }

    private void notifyListener(final ServiceDiscoveryListener listener, final ServiceEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException ex) {
            listenerFailures.add(ZeroException.of(
                    DiscoveryErrorCode.LISTENER_FAILED,
                    "service discovery listener failed: " + event.serviceName(),
                    ex));
        }
    }

    private String instanceKey(final String groupName, final String instanceId) {
        return groupName + '\u0000' + instanceId;
    }

    /**
     * 本地服务发现订阅。
     *
     * @author zn
     */
    private final class LocalSubscription implements ServiceSubscription {

        /**
         * 查询条件。
         */
        private final ServiceQuery query;

        /**
         * 监听器。
         */
        private final ServiceDiscoveryListener listener;

        /**
         * 是否已关闭。
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 创建本地订阅。
         *
         * @param query 查询条件；不可为空。
         * @param listener 监听器；不可为空。
         */
        private LocalSubscription(final ServiceQuery query, final ServiceDiscoveryListener listener) {
            this.query = query;
            this.listener = listener;
        }

        /**
         * 通知匹配的最终快照。
         *
         * @param type 事件类型；不可为空。
         * @param serviceName 服务名称；不可为空。
         * @param groupName 服务分组；不可为空。
         */
        private void notifyIfMatches(final ServiceEventType type, final String serviceName, final String groupName) {
            if (closed.get() || !query.serviceName().equals(serviceName) || !query.groupName().equals(groupName)) {
                return;
            }
            ServiceEvent event = new ServiceEvent(
                    type == ServiceEventType.SNAPSHOT_CHANGED ? type : ServiceEventType.SNAPSHOT_CHANGED,
                    query.serviceName(),
                    query.groupName(),
                    query.clusters(),
                    lookupInternal(query),
                    Instant.now());
            notifyListener(listener, event);
        }

        /**
         * 取消订阅。
         */
        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
            }
        }
    }
}
