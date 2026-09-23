package group.zn.zero.discovery.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.discovery.DiscoveryErrorCode;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceDiscoveryListener;
import group.zn.zero.discovery.ServiceEvent;
import group.zn.zero.discovery.ServiceEventType;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Nacos 3.x 服务发现适配器。
 *
 * <p>该适配器只把 Nacos 作为可选服务发现策略之一，不改变本地内存注册表策略，也不改变 RPC
 * 传输语义。SDK 实例在生命周期内复用，停止时会取消订阅、注销本进程登记的实例并关闭 SDK。
 *
 * @author zn
 */
public final class NacosDiscoveryAdapter extends AbstractLifecycle implements ServiceDiscovery {

    /** Nacos 就绪与启动期调用之间的最大重试间隔。 */
    private static final long MAX_RETRY_PAUSE_NANOS =
            java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100L);

    /**
     * Nacos 配置。
     */
    private final NacosDiscoverySettings settings;

    /**
     * Nacos 命名服务客户端创建工厂。
     */
    private final NacosNamingServiceFactory namingServiceFactory;

    /**
     * 当前进程注册过的服务实例。
     */
    private final ConcurrentMap<InstanceKey, ServiceInstance> registeredInstances = new ConcurrentHashMap<>();

    /**
     * 全局服务事件监听器。
     */
    private final CopyOnWriteArrayList<ServiceDiscoveryListener> listeners = new CopyOnWriteArrayList<>();

    /**
     * Nacos 订阅状态。
     */
    private final CopyOnWriteArrayList<NacosSubscription> subscriptions = new CopyOnWriteArrayList<>();

    /**
     * 监听器失败记录。
     */
    private final CopyOnWriteArrayList<ZeroException> listenerFailures = new CopyOnWriteArrayList<>();

    /**
     * Nacos 命名服务客户端。
     */
    private volatile NamingService namingService;

    /**
     * 创建 Nacos 服务发现适配器。
     */
    public NacosDiscoveryAdapter() {
        this(NacosDiscoverySettings.of("127.0.0.1:8848"));
    }

    /**
     * 创建 Nacos 服务发现适配器。
     *
     * @param settings Nacos 配置；不可为空。
     * @throws NullPointerException 当配置为空时抛出。
     */
    public NacosDiscoveryAdapter(final NacosDiscoverySettings settings) {
        this(settings, NacosFactory::createNamingService);
    }

    /**
     * 使用指定客户端工厂创建 Nacos 服务发现适配器。
     *
     * <p>该构造器只作为包内生命周期测试接缝，不暴露到公共 API。
     *
     * @param settings Nacos 配置；不可为空。
     * @param namingServiceFactory 客户端创建工厂；不可为空。
     * @throws NullPointerException 当任一参数为空时抛出。
     */
    NacosDiscoveryAdapter(
            final NacosDiscoverySettings settings,
            final NacosNamingServiceFactory namingServiceFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.namingServiceFactory = Objects.requireNonNull(namingServiceFactory, "namingServiceFactory");
    }

    /**
     * 返回适配器名称。
     *
     * @return 适配器名称；不可为空；线程安全。
     */
    public String adapterName() {
        return "nacos";
    }

    /**
     * 返回 Nacos 配置。
     *
     * @return Nacos 配置；不可为空；线程安全。
     */
    public NacosDiscoverySettings settings() {
        return settings;
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
        ServiceInstance checked = Objects.requireNonNull(instance, "instance");
        callWithStartupRetry("register Nacos instance failed: " + checked.serviceName(), () -> {
            namingService.registerInstance(checked.serviceName(), checked.groupName(), toNacosInstance(checked));
            return null;
        });
        registeredInstances.put(new InstanceKey(checked.serviceName(), checked.groupName(), checked.instanceId()), checked);
        notifySnapshot(ServiceEventType.REGISTERED, checked.serviceName(), checked.groupName(), List.of(checked.clusterName()));
    }

    /**
     * 注销默认分组中的服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @param instanceId 实例标识；不可为空。
     */
    @Override
    public void unregister(final String serviceName, final String instanceId) {
        unregister(serviceName, settings.defaultGroupName(), instanceId);
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
        InstanceKey key = new InstanceKey(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(groupName, "groupName"),
                Objects.requireNonNull(instanceId, "instanceId"));
        ServiceInstance removed = registeredInstances.get(key);
        if (removed == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "instance not found: " + instanceId, null);
        }
        callWithStartupRetry("deregister Nacos instance failed: " + removed.serviceName(), () -> {
            namingService.deregisterInstance(removed.serviceName(), removed.groupName(), toNacosInstance(removed));
            return null;
        });
        registeredInstances.remove(key);
        notifySnapshot(ServiceEventType.UNREGISTERED, removed.serviceName(), removed.groupName(), List.of(removed.clusterName()));
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
        updateHealth(serviceName, settings.defaultGroupName(), instanceId, healthy);
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
        InstanceKey key = new InstanceKey(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(groupName, "groupName"),
                Objects.requireNonNull(instanceId, "instanceId"));
        ServiceInstance current = registeredInstances.get(key);
        if (current == null) {
            throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND, "instance not found: " + instanceId, null);
        }
        ServiceInstance updated = current.withHealthy(healthy);
        switch (settings.healthUpdateMode()) {
            case LOCAL_ONLY -> {
                registeredInstances.put(key, updated);
                notifySnapshot(ServiceEventType.HEALTH_CHANGED, serviceName, groupName, List.of(updated.clusterName()));
            }
            case REREGISTER -> register(updated);
            case FAIL_FAST -> throw ZeroException.of(
                    DiscoveryErrorCode.HEALTH_UPDATE_UNSUPPORTED,
                    "Nacos health update is disabled by settings",
                    null);
            default -> throw ZeroException.of(
                    DiscoveryErrorCode.HEALTH_UPDATE_UNSUPPORTED,
                    "unknown health update mode: " + settings.healthUpdateMode(),
                    null);
        }
    }

    /**
     * 查询默认分组中的服务实例。
     *
     * @param serviceName 服务名称；不可为空。
     * @return 服务实例列表；不可为空；可能为空；返回集合不可变；无承诺顺序；线程安全。
     */
    @Override
    public List<ServiceInstance> lookup(final String serviceName) {
        return lookup(new ServiceQuery(
                serviceName,
                settings.defaultGroupName(),
                List.of(),
                false,
                false));
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
        ServiceQuery checked = Objects.requireNonNull(query, "query");
        List<Instance> instances = callWithStartupRetry("lookup Nacos instances failed: " + checked.serviceName(), () -> checked.healthyOnly()
                    ? namingService.selectInstances(
                            checked.serviceName(),
                            checked.groupName(),
                            checked.clusters(),
                            true,
                            checked.subscribe())
                    : namingService.getAllInstances(
                            checked.serviceName(),
                            checked.groupName(),
                            checked.clusters(),
                            checked.subscribe()));
        return instances.stream()
                .map(instance -> fromNacosInstance(checked.serviceName(), checked.groupName(), instance))
                .toList();
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
        ServiceQuery checkedQuery = Objects.requireNonNull(query, "query");
        ServiceDiscoveryListener checkedListener = Objects.requireNonNull(listener, "listener");
        NacosSubscription subscription = new NacosSubscription(checkedQuery, checkedListener);
        callWithStartupRetry("subscribe Nacos service failed: " + checkedQuery.serviceName(), () -> {
            namingService.subscribe(
                    checkedQuery.serviceName(),
                    checkedQuery.groupName(),
                    checkedQuery.clusters(),
                    subscription.eventListener());
            return null;
        });
        subscriptions.add(subscription);
        return subscription;
    }

    /**
     * 返回当前进程注册快照。
     *
     * @return 不可变、无序、可能为空、线程安全的注册表快照。
     */
    @Override
    public Map<String, List<ServiceInstance>> snapshot() {
        return Map.copyOf(registeredInstances.values().stream()
                .collect(Collectors.groupingBy(ServiceInstance::serviceName, Collectors.toUnmodifiableList())));
    }

    /**
     * 启动 Nacos SDK。
     */
    @Override
    protected void doStart() {
        try {
            NamingService created = namingServiceFactory.create(settings.toProperties());
            try {
                waitUntilReady(created);
            } catch (RuntimeException | Error ex) {
                ZeroException startupFailure = startupError(ex);
                preserveStartupCleanupFailure(startupFailure, created);
                throw startupFailure;
            }
            namingService = created;
        } catch (NacosException ex) {
            throw nacosError("create Nacos naming service failed", ex);
        }
    }

    /**
     * 注销本进程实例、取消订阅并关闭 Nacos SDK。
     */
    @Override
    protected void doStop() {
        NamingService current = namingService;
        if (current == null) {
            return;
        }
        ZeroException firstFailure = null;
        for (NacosSubscription subscription : List.copyOf(subscriptions)) {
            try {
                subscription.closeWith(current);
            } catch (ZeroException ex) {
                firstFailure = appendFailure(firstFailure, ex);
            } catch (RuntimeException | Error ex) {
                firstFailure = appendFailure(
                        firstFailure,
                        cleanupError("unsubscribe Nacos service during stop failed", ex));
            }
        }
        for (ServiceInstance instance : List.copyOf(registeredInstances.values())) {
            try {
                current.deregisterInstance(instance.serviceName(), instance.groupName(), toNacosInstance(instance));
            } catch (NacosException ex) {
                firstFailure = appendFailure(
                        firstFailure,
                        nacosError("deregister Nacos instance during stop failed: " + instance.serviceName(), ex));
            } catch (RuntimeException | Error ex) {
                firstFailure = appendFailure(
                        firstFailure,
                        cleanupError("deregister Nacos instance during stop failed: " + instance.serviceName(), ex));
            }
        }
        registeredInstances.clear();
        try {
            current.shutDown();
        } catch (NacosException ex) {
            firstFailure = appendFailure(firstFailure, nacosError("shutdown Nacos naming service failed", ex));
        } catch (RuntimeException | Error ex) {
            firstFailure = appendFailure(firstFailure, cleanupError("shutdown Nacos naming service failed", ex));
        } finally {
            namingService = null;
            subscriptions.clear();
        }
        if (firstFailure != null) {
            throw firstFailure;
        }
    }

    private void ensureAvailable() {
        if (!running() || namingService == null) {
            throw ZeroException.of(DiscoveryErrorCode.REGISTRY_UNAVAILABLE, "Nacos registry is unavailable", null);
        }
    }

    private Instance toNacosInstance(final ServiceInstance instance) {
        Instance nacosInstance = new Instance();
        nacosInstance.setInstanceId(instance.instanceId());
        nacosInstance.setIp(instance.host());
        nacosInstance.setPort(instance.port());
        nacosInstance.setClusterName(instance.clusterName());
        nacosInstance.setHealthy(instance.healthy());
        nacosInstance.setEnabled(instance.enabled());
        nacosInstance.setEphemeral(instance.ephemeral());
        nacosInstance.setWeight(instance.weight());
        nacosInstance.setMetadata(instance.metadata());
        return nacosInstance;
    }

    private ServiceInstance fromNacosInstance(final String serviceName, final String groupName, final Instance instance) {
        String clusterName = instance.getClusterName() == null || instance.getClusterName().isBlank()
                ? settings.defaultClusterName()
                : instance.getClusterName();
        String instanceId = instance.getInstanceId() == null || instance.getInstanceId().isBlank()
                ? instance.getIp() + ':' + instance.getPort() + ':' + clusterName
                : instance.getInstanceId();
        Map<String, String> metadata = instance.getMetadata() == null ? Map.of() : instance.getMetadata();
        return new ServiceInstance(
                serviceName,
                instanceId,
                instance.getIp(),
                instance.getPort(),
                groupName,
                clusterName,
                instance.isHealthy(),
                instance.isEnabled(),
                instance.isEphemeral(),
                instance.getWeight(),
                1.0D,
                metadata);
    }

    private void notifySnapshot(
            final ServiceEventType type,
            final String serviceName,
            final String groupName,
            final List<String> clusters) {
        ServiceQuery query = new ServiceQuery(serviceName, groupName, clusters, false, true);
        ServiceEvent event = new ServiceEvent(type, serviceName, groupName, clusters, lookup(query), Instant.now());
        for (ServiceDiscoveryListener listener : listeners) {
            notifyListener(listener, event);
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

    private ZeroException nacosError(final String message, final NacosException exception) {
        return ZeroException.of(DiscoveryErrorCode.NACOS_CLIENT_ERROR, message, exception);
    }

    private ZeroException cleanupError(final String message, final Throwable exception) {
        if (exception instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(DiscoveryErrorCode.NACOS_CLIENT_ERROR, message, exception);
    }

    private ZeroException startupError(final Throwable exception) {
        if (exception instanceof ZeroException zeroException) {
            return zeroException;
        }
        return ZeroException.of(
                DiscoveryErrorCode.REGISTRY_UNAVAILABLE,
                "Nacos naming service readiness failed",
                exception);
    }

    private ZeroException appendFailure(final ZeroException firstFailure, final ZeroException nextFailure) {
        if (firstFailure == null) {
            return nextFailure;
        }
        addSuppressedIfDistinct(firstFailure, nextFailure);
        return firstFailure;
    }

    private void preserveStartupCleanupFailure(final Throwable primaryFailure, final NamingService created) {
        try {
            created.shutDown();
        } catch (NacosException ex) {
            addSuppressedIfDistinct(
                    primaryFailure,
                    nacosError("shutdown Nacos naming service after start failure failed", ex));
        } catch (RuntimeException | Error ex) {
            addSuppressedIfDistinct(
                    primaryFailure,
                    cleanupError("shutdown Nacos naming service after start failure failed", ex));
        }
    }

    private void addSuppressedIfDistinct(final Throwable primaryFailure, final Throwable cleanupFailure) {
        if (primaryFailure != cleanupFailure) {
            primaryFailure.addSuppressed(cleanupFailure);
        }
    }

    private void waitUntilReady(final NamingService service) {
        long deadline = requestDeadline();
        while (remainingNanos(deadline) > 0L) {
            String status = service.getServerStatus();
            if ("UP".equalsIgnoreCase(status)) {
                return;
            }
            sleepBeforeRetry(deadline, "interrupted while waiting Nacos naming service ready");
        }
        throw ZeroException.of(
                DiscoveryErrorCode.REGISTRY_UNAVAILABLE,
                "Nacos naming service is not ready within configured timeout",
                null);
    }

    private List<String> clustersFromEvent(final NamingEvent event, final ServiceQuery fallbackQuery) {
        if (event.getClusters() == null || event.getClusters().isBlank()) {
            return fallbackQuery.clusters();
        }
        return Arrays.stream(event.getClusters().split(","))
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .toList();
    }

    /**
     * Nacos 服务实例索引键。
     *
     * @param serviceName 服务名称。
     * @param groupName 服务分组。
     * @param instanceId 实例标识。
     * @author zn
     */
    private record InstanceKey(String serviceName, String groupName, String instanceId) {

        /**
         * 创建索引键。
         *
         * @throws NullPointerException 当任一字段为空时抛出。
         */
        private InstanceKey {
            Objects.requireNonNull(serviceName, "serviceName");
            Objects.requireNonNull(groupName, "groupName");
            Objects.requireNonNull(instanceId, "instanceId");
        }
    }

    /**
     * Nacos 订阅状态。
     *
     * @author zn
     */
    private final class NacosSubscription implements ServiceSubscription {

        /**
         * 查询条件。
         */
        private final ServiceQuery query;

        /**
         * 用户监听器。
         */
        private final ServiceDiscoveryListener listener;

        /**
         * Nacos SDK 事件监听器。
         */
        private final com.alibaba.nacos.api.naming.listener.EventListener eventListener;

        /**
         * 是否已关闭。
         */
        private final AtomicBoolean closed = new AtomicBoolean();

        /**
         * 创建 Nacos 订阅状态。
         *
         * @param query 查询条件；不可为空。
         * @param listener 用户监听器；不可为空。
         */
        private NacosSubscription(final ServiceQuery query, final ServiceDiscoveryListener listener) {
            this.query = query;
            this.listener = listener;
            this.eventListener = this::onNacosEvent;
        }

        /**
         * 返回 Nacos SDK 事件监听器。
         *
         * @return 监听器；不可为空；线程安全。
         */
        private com.alibaba.nacos.api.naming.listener.EventListener eventListener() {
            return eventListener;
        }

        /**
         * 处理 Nacos 推送事件。
         *
         * @param event Nacos 事件；不可为空。
         */
        private void onNacosEvent(final Event event) {
            if (closed.get() || !(event instanceof NamingEvent namingEvent)) {
                return;
            }
            String serviceName = namingEvent.getServiceName() == null ? query.serviceName() : namingEvent.getServiceName();
            String groupName = namingEvent.getGroupName() == null ? query.groupName() : namingEvent.getGroupName();
            List<String> clusters = clustersFromEvent(namingEvent, query);
            List<ServiceInstance> instances = namingEvent.getInstances() == null
                    ? List.of()
                    : namingEvent.getInstances().stream()
                            .map(instance -> fromNacosInstance(serviceName, groupName, instance))
                            .toList();
            ServiceEvent serviceEvent = new ServiceEvent(
                    ServiceEventType.SNAPSHOT_CHANGED,
                    serviceName,
                    groupName,
                    clusters,
                    instances,
                    Instant.now());
            notifyListener(listener, serviceEvent);
            for (ServiceDiscoveryListener globalListener : listeners) {
                notifyListener(globalListener, serviceEvent);
            }
        }

        /**
         * 取消订阅。
         */
        @Override
        public void close() {
            NamingService current = namingService;
            if (current == null) {
                closed.set(true);
                subscriptions.remove(this);
                return;
            }
            closeWith(current);
        }

        /**
         * 使用指定客户端取消订阅。
         *
         * @param current Nacos 命名服务；不可为空。
         */
        private void closeWith(final NamingService current) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                callWithStartupRetry("unsubscribe Nacos service failed: " + query.serviceName(), () -> {
                    current.unsubscribe(query.serviceName(), query.groupName(), query.clusters(), eventListener);
                    return null;
                });
            } catch (RuntimeException | Error ex) {
                closed.set(false);
                throw ex;
            }
            subscriptions.remove(this);
        }
    }

    private <T> T callWithStartupRetry(final String message, final NacosCall<T> call) {
        long deadline = requestDeadline();
        NacosException lastException = null;
        while (remainingNanos(deadline) > 0L) {
            try {
                return call.execute();
            } catch (NacosException ex) {
                if (!isClientStarting(ex)) {
                    throw nacosError(message, ex);
                }
                lastException = ex;
                sleepBeforeRetry(deadline, "interrupted while retrying Nacos operation");
            }
        }
        throw nacosError(message, lastException);
    }

    private boolean isClientStarting(final NacosException exception) {
        String message = exception.getMessage();
        return message != null && (message.contains("current status:STARTING") || message.contains("Client not connected"));
    }

    private long requestDeadline() {
        return System.nanoTime()
                + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(settings.requestTimeoutMillis());
    }

    private long remainingNanos(final long deadline) {
        return Math.max(0L, deadline - System.nanoTime());
    }

    private void sleepBeforeRetry(final long deadline, final String interruptionMessage) {
        long pauseNanos = Math.min(MAX_RETRY_PAUSE_NANOS, remainingNanos(deadline));
        if (pauseNanos <= 0L) {
            return;
        }
        try {
            java.util.concurrent.TimeUnit.NANOSECONDS.sleep(pauseNanos);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw ZeroException.of(
                    DiscoveryErrorCode.REGISTRY_UNAVAILABLE,
                    interruptionMessage,
                    ex);
        }
    }

    @FunctionalInterface
    private interface NacosCall<T> {

        /**
         * 执行 Nacos 调用。
         *
         * @return 调用结果；可为空。
         * @throws NacosException Nacos SDK 调用失败时抛出。
         */
        T execute() throws NacosException;
    }
}
