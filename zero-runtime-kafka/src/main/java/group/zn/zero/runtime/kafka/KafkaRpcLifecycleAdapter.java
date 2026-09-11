package group.zn.zero.runtime.kafka;

import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.rpc.error.RpcErrorCode;
import group.zn.zero.rpc.kafka.KafkaRpcAdapter;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.rpc.RpcRequest;
import group.zn.zero.rpc.RpcResponse;
import group.zn.zero.rpc.spi.RpcHandler;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcRoute;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import java.util.concurrent.CompletionStage;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * 受 starter 生命周期托管的 Kafka RPC Adapter。
 *
 * <p>该适配器允许业务在 runtime start 之前注册 RPC handler，start 时才创建真实 `KafkaRpcAdapter`
 * 并回放注册表，避免 build 阶段隐式连接或启动 consumer。
 *
 * @author zn
 */
public final class KafkaRpcLifecycleAdapter extends AbstractLifecycle
        implements RpcTransport, RpcHandlerRegistry, AutoCloseable {

    /**
     * Kafka RPC 配置。
     */
    private final KafkaRpcSettings settings;

    /**
     * RPC 传输观测器。
     */
    private final RpcTransportObserver observer;

    /** 创建尚未发布 Kafka RPC 资源的工厂。 */
    private final KafkaRpcAdapterResourceFactory resourceFactory;

    /** handler replay、动态注册、发布与关闭共用的冷路径线性化监视器。 */
    private final Object handlerLifecycleMonitor = new Object();

    /**
     * 延迟回放的 handler 注册表；仅允许在 {@link #handlerLifecycleMonitor} 内访问。
     */
    private final Map<RpcRoute, HandlerRegistration> handlers = new LinkedHashMap<>();

    /**
     * 已发布 Kafka RPC 资源；为空表示尚未启动或已经停止。
     */
    private volatile KafkaRpcAdapterResource delegate;

    /**
     * 创建生命周期托管 Kafka RPC Adapter。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     */
    KafkaRpcLifecycleAdapter(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer) {
        this(settings, observer, KafkaRpcAdapterResourceFactory.production());
    }

    /**
     * 创建允许注入发布前资源工厂的 Kafka RPC lifecycle adapter。
     *
     * <p>该包级构造器只服务启动回放与回滚故障注入测试；生产装配使用双参数构造器。
     *
     * @param settings Kafka RPC 配置；不可为空。
     * @param observer RPC 传输观测器；不可为空。
     * @param resourceFactory 发布前 Kafka RPC 资源工厂；不可为空。
     */
    KafkaRpcLifecycleAdapter(
            final KafkaRpcSettings settings,
            final RpcTransportObserver observer,
            final KafkaRpcAdapterResourceFactory resourceFactory) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.observer = Objects.requireNonNull(observer, "observer");
        this.resourceFactory = Objects.requireNonNull(resourceFactory, "resourceFactory");
    }

    /**
     * 返回当前 lifecycle 使用的 Kafka RPC 配置快照。
     *
     * <p>该包级冷路径 seam 用于验证 Builder 属性传播，不创建连接、不修改 Adapter 状态。
     *
     * @return 不可变 Kafka RPC 配置；不可为空，线程安全。
     */
    KafkaRpcSettings settings() {
        return settings;
    }

    /**
     * 返回 provider 名称。
     *
     * @return provider 名称；不可为空，线程安全。
     */
    @Override
    public String name() {
        return "kafka";
    }

    /**
     * 发送 request/response 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 响应阶段；不可为空，线程安全。
     * @throws ZeroException 当 Adapter 尚未启动或发送失败时抛出。
     */
    @Override
    public CompletionStage<RpcResponse> request(final RpcRequest request) {
        return requireDelegate().request(request);
    }

    /**
     * 发送 oneway 请求。
     *
     * @param request RPC 请求；不可为空。
     * @return 发送完成阶段；不可为空，线程安全。
     * @throws ZeroException 当 Adapter 尚未启动或发送失败时抛出。
     */
    @Override
    public CompletionStage<Void> oneway(final RpcRequest request) {
        return requireDelegate().oneway(request);
    }

    /**
     * 注册 RPC handler。
     *
     * <p>线程安全：委托完整入口与 start/close 线性化；会更新 replay map，并可能发布真实 subscription。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param handler handler；不可为空。
     * @throws NullPointerException 任一参数为空时抛出。
     * @throws ProductionAdapterException 已启动 Adapter 的注册或补偿失败时抛出安全异常。
     */
    @Override
    public void register(final String serviceName, final String methodName, final RpcHandler handler) {
        register(serviceName, methodName, "", "", handler);
    }

    /**
     * 注册带传输元数据的 RPC handler。
     *
     * <p>线程安全：与 start/close 线性化；更新 replay map，并在已启动时事务性替换真实注册，失败恢复旧值。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @param topic request topic；可为空。
     * @param group consumer group；可为空。
     * @param handler handler；不可为空。
     * @throws NullPointerException 必填参数为空时抛出。
     * @throws ProductionAdapterException 注册或安全补偿失败时抛出。
     */
    @Override
    public void register(
            final String serviceName,
            final String methodName,
            final String topic,
            final String group,
            final RpcHandler handler) {
        RpcRoute route = new RpcRoute(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(methodName, "methodName"));
        HandlerRegistration registration = new HandlerRegistration(
                route,
                topic == null ? "" : topic,
                group == null ? "" : group,
                Objects.requireNonNull(handler, "handler"));
        synchronized (handlerLifecycleMonitor) {
            KafkaRpcAdapterResource current = delegate;
            HandlerRegistration previous = handlers.get(route);
            if (current == null) {
                commitRegistration(route, registration);
                return;
            }
            try {
                registration.registerTo(current);
                commitRegistration(route, registration);
            } catch (RuntimeException | Error failure) {
                ProductionAdapterException safeFailure = registrationFailure(failure);
                restorePublishedRegistration(current, route, previous, safeFailure);
                throw safeFailure;
            }
        }
    }

    /**
     * 注销 RPC handler。
     *
     * <p>线程安全：与 start/close 线性化；同时更新 replay map 与已发布 subscription，失败恢复旧注册。</p>
     *
     * @param serviceName 服务名；不可为空。
     * @param methodName 方法名；不可为空。
     * @throws NullPointerException 任一参数为空时抛出。
     * @throws ProductionAdapterException 注销或安全补偿失败时抛出。
     */
    @Override
    public void unregister(final String serviceName, final String methodName) {
        RpcRoute route = new RpcRoute(
                Objects.requireNonNull(serviceName, "serviceName"),
                Objects.requireNonNull(methodName, "methodName"));
        synchronized (handlerLifecycleMonitor) {
            HandlerRegistration previous = handlers.get(route);
            if (previous == null) {
                return;
            }
            KafkaRpcAdapterResource current = delegate;
            if (current == null) {
                handlers.remove(route);
                return;
            }
            try {
                current.unregister(serviceName, methodName);
                handlers.remove(route);
            } catch (RuntimeException | Error failure) {
                ProductionAdapterException safeFailure = registrationFailure(failure);
                restorePublishedRegistration(current, route, previous, safeFailure);
                throw safeFailure;
            }
        }
    }

    /**
     * 返回真实 Kafka RPC Adapter 快照。
     *
     * @return Adapter 快照；为空表示尚未启动或已经停止，线程安全。
     */
    public Optional<KafkaRpcAdapter> delegate() {
        KafkaRpcAdapterResource current = delegate;
        return current == null ? Optional.empty() : current.exposedAdapter();
    }

    /**
     * 返回发布前资源是否已经完成 handler 回放并被 lifecycle 原子发布。
     *
     * <p>该包级冷路径 seam 用于不创建真实 Kafka client 的事务与并发测试。
     *
     * @return 已发布资源时为 {@code true}；只读且线程安全。
     */
    boolean resourcePublished() {
        return delegate != null;
    }

    /**
     * 启动真实 Kafka RPC Adapter 并回放 handler 注册表。
     */
    @Override
    protected void doStart() {
        synchronized (handlerLifecycleMonitor) {
            KafkaRpcAdapterResource created = null;
            try {
                created = Objects.requireNonNull(
                        resourceFactory.create(settings, observer),
                        "created kafka rpc resource");
                for (HandlerRegistration registration : handlers.values()) {
                    registration.registerTo(created);
                }
                delegate = created;
            } catch (RuntimeException | Error failure) {
                ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                        ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                        ProductionAdapterFailurePhase.STARTUP,
                        ProductionAdapterErrorCode.STARTUP_FAILED,
                        ProductionAdapterErrorCode.STARTUP_FAILED.message(),
                        failure);
                rollbackCreatedAdapter(created, safeFailure);
                throw safeFailure;
            }
        }
    }

    /**
     * 关闭真实 Kafka RPC Adapter。
     */
    @Override
    protected void doStop() {
        closeDelegate();
    }

    /**
     * 通过统一生命周期状态机幂等关闭真实 Kafka RPC Adapter。
     *
     * <p>线程安全：与注册和启动线性化；分离并关闭底层资源，不修改业务数据。</p>
     * @throws ProductionAdapterException 底层关闭失败时抛出固定安全异常。
     */
    @Override
    public void close() {
        stop();
    }

    /**
     * 在线性化监视器内分离并关闭已经发布的资源。
     *
     * @throws ProductionAdapterException 底层关闭失败时抛出不保留原始异常图的安全异常。
     */
    private void closeDelegate() {
        synchronized (handlerLifecycleMonitor) {
            KafkaRpcAdapterResource current = delegate;
            delegate = null;
            if (current == null) {
                return;
            }
            try {
                current.close();
            } catch (RuntimeException | Error failure) {
                throw ProductionAdapterFailures.sanitize(
                        ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                        ProductionAdapterFailurePhase.CLOSE,
                        ProductionAdapterErrorCode.CLOSE_FAILED,
                        ProductionAdapterErrorCode.CLOSE_FAILED.message(),
                        failure);
            }
        }
    }

    /**
     * 在 delegate 操作成功后提交 wrapper replay map。
     *
     * @param route RPC 路由；不可为空。
     * @param registration 新注册信息；不可为空。
     * @throws ProductionAdapterException replay map 提交失败时抛出安全注册异常。
     */
    private void commitRegistration(
            final RpcRoute route,
            final HandlerRegistration registration) {
        try {
            handlers.put(route, registration);
        } catch (RuntimeException | Error failure) {
            throw registrationFailure(failure);
        }
    }

    /**
     * 把动态注册或注销失败转换为固定安全异常。
     *
     * @param failure 原始失败；可为空且不会被保留。
     * @return 安全注册异常；不可为空，cause 为空。
     */
    private ProductionAdapterException registrationFailure(final Throwable failure) {
        return ProductionAdapterFailures.sanitize(
                ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                ProductionAdapterFailurePhase.REGISTRATION,
                ProductionAdapterErrorCode.REGISTRATION_FAILED,
                ProductionAdapterErrorCode.REGISTRATION_FAILED.message(),
                failure);
    }

    /**
     * 动态注册事务失败后恢复已发布资源的上一注册状态，并安全聚合恢复失败。
     *
     * @param current 已发布 Kafka RPC 资源；不可为空。
     * @param route 本次修改的路由；不可为空。
     * @param previous wrapper 事务开始前的注册；可为空。
     * @param primaryFailure 安全主异常；不可为空，本方法可能追加一个安全 suppressed。
     */
    private void restorePublishedRegistration(
            final KafkaRpcAdapterResource current,
            final RpcRoute route,
            final HandlerRegistration previous,
            final ProductionAdapterException primaryFailure) {
        try {
            if (previous == null) {
                current.unregister(route.serviceName(), route.methodName());
            } else {
                previous.registerTo(current);
            }
        } catch (RuntimeException | Error rollbackFailure) {
            primaryFailure.addSuppressed(ProductionAdapterFailures.reclassify(
                    ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                    ProductionAdapterFailurePhase.ROLLBACK,
                    ProductionAdapterErrorCode.ROLLBACK_FAILED,
                    ProductionAdapterErrorCode.ROLLBACK_FAILED.message(),
                    rollbackFailure));
        }
    }

    private void rollbackCreatedAdapter(
            final KafkaRpcAdapterResource created,
            final ProductionAdapterException primaryFailure) {
        if (created == null) {
            return;
        }
        try {
            created.close();
        } catch (RuntimeException | Error closeFailure) {
            primaryFailure.addSuppressed(ProductionAdapterFailures.sanitize(
                    ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                    ProductionAdapterFailurePhase.ROLLBACK,
                    ProductionAdapterErrorCode.ROLLBACK_FAILED,
                    ProductionAdapterErrorCode.ROLLBACK_FAILED.message(),
                    closeFailure));
        }
    }

    private KafkaRpcAdapterResource requireDelegate() {
        KafkaRpcAdapterResource current = delegate;
        if (current == null) {
            throw ZeroException.of(
                    RpcErrorCode.TRANSPORT_UNAVAILABLE,
                    "kafka rpc adapter is not started",
                    null);
        }
        return current;
    }

    /**
     * 延迟 handler 注册信息。
     *
     * @param route RPC 路由。
     * @param topic request topic。
     * @param group consumer group。
     * @param handler RPC handler。
     * @author zn
     */
    private record HandlerRegistration(RpcRoute route, String topic, String group, RpcHandler handler) {

        /**
         * 创建延迟 handler 注册信息。
         *
         * @throws NullPointerException 当必填字段为空时抛出。
         */
        private HandlerRegistration {
            Objects.requireNonNull(route, "route");
            Objects.requireNonNull(topic, "topic");
            Objects.requireNonNull(group, "group");
            Objects.requireNonNull(handler, "handler");
        }

        /**
         * 将注册信息写入尚未发布或已经发布的 Kafka RPC 资源。
         *
         * @param resource Kafka RPC 资源；不可为空。
         */
        private void registerTo(final KafkaRpcAdapterResource resource) {
            Objects.requireNonNull(resource, "resource")
                    .register(route.serviceName(), route.methodName(), topic, group, handler);
        }
    }
}
