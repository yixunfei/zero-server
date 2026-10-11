package group.zn.zero.discovery.nacos;

import com.alibaba.nacos.api.exception.NacosException;
import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.listener.Event;
import com.alibaba.nacos.api.naming.listener.NamingEvent;
import com.alibaba.nacos.api.naming.NamingService;
import com.alibaba.nacos.api.naming.pojo.Instance;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.discovery.DiscoveryErrorCode;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.discovery.ServiceDiscoveryListener;
import group.zn.zero.discovery.ServiceEvent;
import group.zn.zero.discovery.ServiceEventType;
import group.zn.zero.discovery.ServiceInstance;
import group.zn.zero.discovery.ServiceQuery;
import group.zn.zero.discovery.ServiceSubscription;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
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

    /** 短期 lookup 缓存，避免路由热路径每次穿透 Nacos；TTL 可由 extraProperties 配置。 */
    private final ConcurrentMap<ServiceQuery, LookupCacheEntry> lookupCache = new ConcurrentHashMap<>();

    /** 缓存读写及失效代际短锁；禁止在锁内调用 SDK 或业务监听器。 */
    private final Object lookupMonitor = new Object();

    /** 失效代际，仅在 lookupMonitor 内访问；跨服务事件也禁止旧查询回填。 */
    private long lookupRevision;

    /** lookup 缓存 TTL，默认一秒；配置为 0 可关闭缓存。 */
    private final long lookupCacheTtlNanos;

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
    private final java.util.ArrayDeque<ZeroException> listenerFailures = new java.util.ArrayDeque<>();

    /** 监听失败累计数，仅在 listenerFailures 锁内访问。 */
    private long listenerFailureCount;

    /**
     * Nacos 命名服务客户端。
     */
    private volatile NamingService namingService;

    /** SDK 回调只排队，生命周期锁释放后再执行用户代码。 */
    private final ConcurrentLinkedQueue<Runnable> notifications = new ConcurrentLinkedQueue<>();

    /** 通知单消费者标记。 */
    private final AtomicBoolean drainingNotifications = new AtomicBoolean();

    /** SDK 操作计数；增减和等待在实例短锁内，SDK 调用在锁外。 */
    private volatile int sdkOperationDepth;

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
        this.lookupCacheTtlNanos = parseLookupCacheTtl(settings.extraProperties());
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

    /** Checks the live SDK connection state without reading the local lookup cache. */
    public void probeConnection() {
        withClient(current -> {
            if (!"UP".equalsIgnoreCase(current.getServerStatus())) {
                throw ZeroException.of(DiscoveryErrorCode.REGISTRY_UNAVAILABLE);
            }
            return null;
        });
    }

    /**
     * 返回监听器失败快照。
     *
     * @return 最近最多 256 条，按发生顺序排列的不可变快照；可能为空，线程安全。
     */
    public List<ZeroException> listenerFailures() {
        synchronized (listenerFailures) { return List.copyOf(listenerFailures); }
    }

    /** @return 累计监听失败数；线程安全，只读，不随历史截断而清零。 */
    public long listenerFailureCount() {
        synchronized (listenerFailures) { return listenerFailureCount; }
    }

    /** @return 因容量限制丢弃的历史记录数；线程安全，只读。 */
    public long droppedListenerFailureCount() {
        synchronized (listenerFailures) { return listenerFailureCount - listenerFailures.size(); }
    }

    /**
     * 注册服务实例。
     *
     * @param instance 服务实例；不可为空。
     */
    @Override
    public void register(final ServiceInstance instance) {
        ServiceInstance checked = Objects.requireNonNull(instance, "instance");
        withClient(current -> {
            registerInstance(current, checked);
            notifySnapshot(ServiceEventType.REGISTERED, checked.serviceName(), checked.groupName(),
                    List.of(checked.clusterName()), current);
            return null;
        });
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
        InstanceKey key = new InstanceKey(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(groupName, "groupName"),
                Objects.requireNonNull(instanceId, "instanceId"));
        withClient(current -> {
            ServiceInstance removed = registeredInstances.get(key);
            if (removed == null) {
                throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND,
                        "instance not found: " + instanceId, null);
            }
            callWithStartupRetry("deregister Nacos instance failed: " + removed.serviceName(), () -> {
                current.deregisterInstance(removed.serviceName(), removed.groupName(), toNacosInstance(removed));
                return null;
            });
            registeredInstances.remove(key, removed);
            invalidateLookupCache();
            notifySnapshot(ServiceEventType.UNREGISTERED, removed.serviceName(), removed.groupName(),
                    List.of(removed.clusterName()), current);
            return null;
        });
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
        InstanceKey key = new InstanceKey(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(groupName, "groupName"),
                Objects.requireNonNull(instanceId, "instanceId"));
        withClient(client -> {
            ServiceInstance current = registeredInstances.get(key);
            if (current == null) {
                throw ZeroException.of(DiscoveryErrorCode.INSTANCE_NOT_FOUND,
                        "instance not found: " + instanceId, null);
            }
            ServiceInstance updated = current.withHealthy(healthy);
            switch (settings.healthUpdateMode()) {
                case LOCAL_ONLY -> registeredInstances.put(key, updated);
                case REREGISTER -> registerInstance(client, updated);
                case FAIL_FAST -> throw ZeroException.of(
                        DiscoveryErrorCode.HEALTH_UPDATE_UNSUPPORTED,
                        "Nacos health update is disabled by settings", null);
                default -> throw ZeroException.of(
                        DiscoveryErrorCode.HEALTH_UPDATE_UNSUPPORTED,
                        "unknown health update mode: " + settings.healthUpdateMode(), null);
            }
            invalidateLookupCache();
            notifySnapshot(ServiceEventType.HEALTH_CHANGED, serviceName, groupName,
                    List.of(updated.clusterName()), client);
            return null;
        });
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
        ServiceQuery checked = Objects.requireNonNull(query, "query");
        return withClient(current -> lookupCached(current, checked));
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
        ServiceQuery checkedQuery = Objects.requireNonNull(query, "query");
        ServiceDiscoveryListener checkedListener = Objects.requireNonNull(listener, "listener");
        return withClient(current -> {
            NacosSubscription subscription = new NacosSubscription(current, checkedQuery, checkedListener);
            try {
                callWithStartupRetry("subscribe Nacos service failed: " + checkedQuery.serviceName(), () -> {
                    current.subscribe(checkedQuery.serviceName(), checkedQuery.groupName(), checkedQuery.clusters(),
                            subscription.eventListener());
                    return null;
                });
                subscriptions.add(subscription);
                return subscription;
            } catch (RuntimeException | Error failure) {
                subscription.closed.set(true);
                try {
                    current.unsubscribe(checkedQuery.serviceName(), checkedQuery.groupName(), checkedQuery.clusters(),
                            subscription.eventListener());
                } catch (NacosException | RuntimeException | Error cleanupFailure) {
                    addSuppressedIfDistinct(failure, cleanupFailure);
                }
                throw failure;
            }
        });
    }

    /**
     * 返回当前进程注册快照。
     *
     * @return 不可变、无序、可能为空、线程安全的注册表快照。
     */
    @Override
    public synchronized Map<String, List<ServiceInstance>> snapshot() {
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
            invalidateLookupCache();
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
        NamingService current;
        synchronized (this) {
            awaitSdkOperations();
            current = namingService;
            if (current == null) {
                return;
            }
            sdkOperationDepth++;
        }
        try {
            ZeroException firstFailure = null;
            for (NacosSubscription subscription : List.copyOf(subscriptions)) {
                try {
                    subscription.closeWith(current, false);
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
            invalidateLookupCache();
            try {
                current.shutDown();
            } catch (NacosException ex) {
                firstFailure = appendFailure(firstFailure, nacosError("shutdown Nacos naming service failed", ex));
            } catch (RuntimeException | Error ex) {
                firstFailure = appendFailure(firstFailure, cleanupError("shutdown Nacos naming service failed", ex));
            } finally {
                namingService = null;
                for (NacosSubscription subscription : subscriptions) {
                    subscription.closed.set(true);
                }
                subscriptions.clear();
                notifications.clear();
            }
            if (firstFailure != null) {
                throw firstFailure;
            }
        } finally {
            synchronized (this) {
                sdkOperationDepth--;
                notifyAll();
            }
        }
    }

    private void ensureAvailable() {
        if (!running() || namingService == null) {
            throw ZeroException.of(DiscoveryErrorCode.REGISTRY_UNAVAILABLE, "Nacos registry is unavailable", null);
        }
    }

    /**
     * 停止前在配置的 SDK 请求预算内等待已获取旧客户端的操作提交。
     *
     * <p>调用方必须持实例锁；{@link Object#wait(long)} 会释放该锁。超时后不关闭仍被使用的
     * client，避免与迟到 SDK 调用并发访问同一资源。</p>
     */
    private void awaitSdkOperations() {
        boolean interrupted = false;
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(
                settings.requestTimeoutMillis());
        while (sdkOperationDepth != 0) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                throw ZeroException.of(DiscoveryErrorCode.REGISTRY_UNAVAILABLE,
                        "Nacos SDK operation did not finish before stop deadline", null);
            }
            try {
                long millis = Math.max(1L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(remainingNanos));
                wait(millis);
            } catch (InterruptedException failure) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** 查询固定客户端并应用统一过滤，调用方必须已在线性化事务内。 */
    private List<ServiceInstance> lookupFromClient(
            final NamingService current,
            final ServiceQuery query) {
        List<Instance> instances = callWithStartupRetry("lookup Nacos instances failed: " + query.serviceName(), () ->
                query.healthyOnly() && settings.healthUpdateMode() != NacosHealthUpdateMode.LOCAL_ONLY
                        ? current.selectInstances(query.serviceName(), query.groupName(), query.clusters(), true,
                                query.subscribe())
                        : current.getAllInstances(query.serviceName(), query.groupName(), query.clusters(),
                                query.subscribe()));
        return convertInstances(query, instances);
    }

    /** 在同一生命周期事务内完成远端注册和本地提交。 */
    private void registerInstance(final NamingService current, final ServiceInstance instance) {
        callWithStartupRetry("register Nacos instance failed: " + instance.serviceName(), () -> {
            current.registerInstance(instance.serviceName(), instance.groupName(), toNacosInstance(instance));
            return null;
        });
        registeredInstances.put(new InstanceKey(instance.serviceName(), instance.groupName(), instance.instanceId()), instance);
        invalidateLookupCache();
    }

    /** 应用服务名、分组、集群和健康过滤。 */
    private boolean matchesQuery(final ServiceQuery query, final ServiceInstance instance) {
        return query.serviceName().equals(instance.serviceName())
                && query.groupName().equals(instance.groupName())
                && (query.clusters().isEmpty() || query.clusters().contains(instance.clusterName()))
                && (!query.healthyOnly() || instance.healthy());
    }

    /** 将一次生命周期操作固定到当前客户端，并在锁外投递通知。 */
    private <T> T withClient(final Function<NamingService, T> operation) {
        NamingService current;
        synchronized (this) {
            ensureAvailable();
            current = namingService;
            sdkOperationDepth++;
        }
        try {
            return operation.apply(current);
        } finally {
            synchronized (this) {
                sdkOperationDepth--;
                notifyAll();
            }
            drainNotifications();
        }
    }

    /** 短期缓存 lookup 结果；缓存只用于读路径，事件通知始终直接查询 SDK。 */
    private List<ServiceInstance> lookupCached(
            final NamingService current,
            final ServiceQuery query) {
        long revision;
        synchronized (lookupMonitor) {
            revision = lookupRevision;
            if (lookupCacheTtlNanos > 0L) {
                LookupCacheEntry cached = lookupCache.get(query);
                if (cached != null && System.nanoTime() - cached.createdAtNanos() < lookupCacheTtlNanos) {
                    return cached.instances();
                }
            }
        }
        List<ServiceInstance> loaded = lookupFromClient(current, query);
        synchronized (lookupMonitor) {
            if (lookupCacheTtlNanos > 0L && revision == lookupRevision && namingService == current) {
                long now = System.nanoTime();
                if (lookupCache.size() >= 4096) {
                    lookupCache.entrySet().removeIf(entry -> now - entry.getValue().createdAtNanos() >= lookupCacheTtlNanos);
                }
                if (lookupCache.size() < 4096 || lookupCache.containsKey(query)) {
                    lookupCache.put(query, new LookupCacheEntry(now, loaded));
                }
            }
        }
        return loaded;
    }

    private void invalidateLookupCache() {
        synchronized (lookupMonitor) {
            lookupRevision++;
            lookupCache.clear();
        }
    }

    /** 排队事件；生命周期锁内或 SDK 操作期间不执行用户代码。 */
    private void enqueueNotification(final Runnable notification) {
        notifications.add(notification);
        drainNotifications();
    }

    /** 单消费者顺序投递通知，监听器重入产生的通知继续排队。 */
    private void drainNotifications() {
        if (Thread.holdsLock(this) || sdkOperationDepth != 0) {
            return;
        }
        do {
            if (!drainingNotifications.compareAndSet(false, true)) {
                return;
            }
            try {
                Runnable notification;
                while (sdkOperationDepth == 0 && (notification = notifications.poll()) != null) {
                    notification.run();
                }
            } finally {
                drainingNotifications.set(false);
            }
        } while (sdkOperationDepth == 0 && !notifications.isEmpty());
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

    /** 隔离坏实例，并把 LOCAL_ONLY 的本地健康覆盖应用到查询与事件快照。 */
    private List<ServiceInstance> convertInstances(final ServiceQuery query, final List<Instance> instances) {
        if (instances == null) return List.of();
        List<ServiceInstance> converted = new java.util.ArrayList<>();
        for (Instance instance : instances) {
            try {
                ServiceInstance value = fromNacosInstance(query.serviceName(), query.groupName(), instance);
                if (settings.healthUpdateMode() == NacosHealthUpdateMode.LOCAL_ONLY) {
                    for (ServiceInstance local : registeredInstances.values()) {
                        if (local.serviceName().equals(value.serviceName()) && local.groupName().equals(value.groupName())
                                && local.clusterName().equals(value.clusterName()) && local.host().equals(value.host())
                                && local.port() == value.port()) {
                            value = value.withHealthy(local.healthy());
                            break;
                        }
                    }
                }
                if (matchesQuery(query, value)) converted.add(value);
            } catch (RuntimeException invalidInstance) {
                synchronized (listenerFailures) {
                    listenerFailureCount++;
                    if (listenerFailures.size() == 256) listenerFailures.removeFirst();
                    listenerFailures.addLast(ZeroException.of(DiscoveryErrorCode.LISTENER_FAILED,
                            "invalid registry instance rejected", invalidInstance));
                }
            }
        }
        return List.copyOf(converted);
    }

    private void notifySnapshot(
            final ServiceEventType type,
            final String serviceName,
            final String groupName,
            final List<String> clusters,
            final NamingService owner) {
        ServiceQuery query = new ServiceQuery(serviceName, groupName, clusters, false, true);
        ServiceEvent event = new ServiceEvent(type, serviceName, groupName, clusters,
                lookupFromClient(owner, query), Instant.now());
        enqueueNotification(() -> {
            if (!running() || namingService != owner) {
                return;
            }
            for (ServiceDiscoveryListener listener : listeners) {
                notifyListener(listener, event);
            }
        });
    }

    private void notifyListener(final ServiceDiscoveryListener listener, final ServiceEvent event) {
        try {
            listener.onEvent(event);
        } catch (RuntimeException ex) {
            ZeroException failure = ZeroException.of(
                    DiscoveryErrorCode.LISTENER_FAILED,
                    "service discovery listener failed: " + event.serviceName(),
                    ex);
            synchronized (listenerFailures) {
                listenerFailureCount++;
                if (listenerFailures.size() == 256) listenerFailures.removeFirst();
                listenerFailures.addLast(failure);
            }
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

    private long parseLookupCacheTtl(final Map<String, String> properties) {
        String configured = properties.get("zero.discovery.nacos.lookup-cache-ttl-millis");
        if (configured == null || configured.isBlank()) {
            return TimeUnit.SECONDS.toNanos(1L);
        }
        try {
            long millis = Long.parseLong(configured);
            if (millis < 0L) {
                throw new NumberFormatException("negative");
            }
            return TimeUnit.MILLISECONDS.toNanos(millis);
        } catch (NumberFormatException failure) {
            throw new IllegalArgumentException(
                    "zero.discovery.nacos.lookup-cache-ttl-millis must be non-negative", failure);
        }
    }

    private record LookupCacheEntry(long createdAtNanos, List<ServiceInstance> instances) {
        private LookupCacheEntry {
            instances = List.copyOf(instances);
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

        /** 创建本订阅的客户端，重启后旧句柄不得触碰新客户端。 */
        private final NamingService ownerClient;

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
        private NacosSubscription(
                final NamingService ownerClient,
                final ServiceQuery query,
                final ServiceDiscoveryListener listener) {
            this.ownerClient = ownerClient;
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
            if (!active() || !(event instanceof NamingEvent namingEvent)) {
                return;
            }
            // 服务端推送时清除该服务的 lookup 缓存，避免故障转移后流量打到已下线实例。
            // 只清匹配该服务的条目，不影响其它服务的缓存。
            synchronized (lookupMonitor) {
                lookupRevision++;
                lookupCache.keySet().removeIf(key ->
                        key.serviceName().equals(query.serviceName()) && key.groupName().equals(query.groupName()));
            }
            List<ServiceInstance> instances = convertInstances(query, namingEvent.getInstances());
            ServiceEvent serviceEvent = new ServiceEvent(
                    ServiceEventType.SNAPSHOT_CHANGED,
                    query.serviceName(),
                    query.groupName(),
                    query.clusters(),
                    instances,
                    Instant.now());
            enqueueNotification(() -> {
                if (!active()) {
                    return;
                }
                notifyListener(listener, serviceEvent);
                for (ServiceDiscoveryListener globalListener : listeners) {
                    if (!active()) {
                        return;
                    }
                    notifyListener(globalListener, serviceEvent);
                }
            });
        }

        /** 当前句柄仍属于正在运行的客户端。 */
        private boolean active() {
            return !closed.get() && running() && namingService == ownerClient;
        }

        /**
         * 取消订阅。
         */
        @Override
        public void close() {
            NamingService current;
            synchronized (NacosDiscoveryAdapter.this) {
                if (state() == LifecycleState.STOPPING) {
                    // 停止流程统一取消订阅，禁止在清理快照之后再准入 SDK 调用。
                    return;
                }
                current = namingService;
                if (current != ownerClient || current == null) {
                    closed.set(true);
                    subscriptions.remove(this);
                    return;
                }
                sdkOperationDepth++;
            }
            try {
                closeWith(current, true);
            } finally {
                synchronized (NacosDiscoveryAdapter.this) {
                    sdkOperationDepth--;
                    NacosDiscoveryAdapter.this.notifyAll();
                }
                drainNotifications();
            }
        }

        /**
         * 使用指定客户端取消订阅。
         *
         * @param current Nacos 命名服务；不可为空。
         */
        private void closeWith(final NamingService current, final boolean retryOnFailure) {
            if (current != ownerClient) {
                closed.set(true);
                subscriptions.remove(this);
                return;
            }
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                callWithStartupRetry("unsubscribe Nacos service failed: " + query.serviceName(), () -> {
                    current.unsubscribe(query.serviceName(), query.groupName(), query.clusters(), eventListener);
                    return null;
                });
            } catch (RuntimeException | Error ex) {
                if (retryOnFailure) {
                    closed.set(false);
                }
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
