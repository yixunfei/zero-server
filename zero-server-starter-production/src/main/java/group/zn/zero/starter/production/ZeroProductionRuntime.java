package group.zn.zero.starter.production;

import com.mongodb.client.MongoClient;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.postgresql.PostgresqlDataAdapter;
import group.zn.zero.data.redis.RedisDataAdapter;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.rpc.discovery.RpcServiceResolver;
import group.zn.zero.starter.ZeroRuntimeComponents;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import redis.clients.jedis.RedisClient;

/**
 * 生产 runtime 门面。
 *
 * <p>该门面包装基础 `ZeroRuntimeComponents`，额外暴露真实 Adapter 实例和脱敏诊断报告。
 * 启动、停止和健康检查仍由统一生命周期顺序管理。
 *
 * @author zn
 */
public final class ZeroProductionRuntime extends AbstractLifecycle implements AutoCloseable {

    /**
     * runtime profile。
     */
    private final String profile;

    /**
     * 基础 starter runtime 组件。
     */
    private final ZeroRuntimeComponents components;

    /**
     * Adapter 诊断状态。
     */
    private final List<ProductionAdapterDiagnostic> diagnostics;

    /**
     * 可在 build 后直接关闭的资源。
     */
    private final List<AutoCloseable> buildCloseables;

    /** 每个 build 资源是否已经成功关闭；索引与 {@link #buildCloseables} 一致。 */
    private final boolean[] buildResourceClosed;

    /** build 资源关闭事务锁。 */
    private final Object buildResourceCloseMonitor = new Object();

    /** 共享累计启动预算。 */
    private final ProductionStartupBudget startupBudget;

    /** 是否已经占用唯一一次启动机会；由当前对象监视器保护。 */
    private boolean startClaimed;

    /** 是否已经请求终止并关闭 runtime；由当前对象监视器保护。 */
    private boolean closeRequested;

    /**
     * Kafka RPC lifecycle adapter。
     */
    private final KafkaRpcLifecycleAdapter kafkaRpcAdapter;

    /**
     * MongoDB data adapter。
     */
    private final MongoDataAdapter mongoDataAdapter;

    /**
     * MongoDB client。
     */
    private final MongoClient mongoClient;

    /**
     * Redis data adapter。
     */
    private final RedisDataAdapter redisDataAdapter;

    /**
     * Redis client。
     */
    private final RedisClient redisClient;

    /**
     * PostgreSQL data adapter。
     */
    private final PostgresqlDataAdapter postgresqlDataAdapter;

    /**
     * 服务发现组件。
     */
    private final ServiceDiscovery serviceDiscovery;

    /**
     * RPC 服务实例解析器。
     */
    private final RpcServiceResolver rpcServiceResolver;

    /**
     * 创建生产 runtime 门面。
     *
     * @param profile runtime profile；不可为空。
     * @param components 基础 starter runtime 组件；不可为空。
     * @param diagnostics Adapter 诊断状态；不可为空。
     * @param buildCloseables 构建后可关闭资源；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param adapters Adapter 实例集合；不可为空。
     */
    ZeroProductionRuntime(
            final String profile,
            final ZeroRuntimeComponents components,
            final List<ProductionAdapterDiagnostic> diagnostics,
            final List<AutoCloseable> buildCloseables,
            final ProductionStartupBudget startupBudget,
            final RuntimeAdapters adapters) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.components = Objects.requireNonNull(components, "components");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        this.buildCloseables = List.copyOf(Objects.requireNonNull(buildCloseables, "buildCloseables"));
        this.buildResourceClosed = new boolean[this.buildCloseables.size()];
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        RuntimeAdapters currentAdapters = Objects.requireNonNull(adapters, "adapters");
        this.kafkaRpcAdapter = currentAdapters.kafkaRpcAdapter();
        this.mongoDataAdapter = currentAdapters.mongoDataAdapter();
        this.mongoClient = currentAdapters.mongoClient();
        this.redisDataAdapter = currentAdapters.redisDataAdapter();
        this.redisClient = currentAdapters.redisClient();
        this.postgresqlDataAdapter = currentAdapters.postgresqlDataAdapter();
        this.serviceDiscovery = currentAdapters.serviceDiscovery();
        this.rpcServiceResolver = currentAdapters.rpcServiceResolver();
    }

    /**
     * 返回基础 starter runtime 组件。
     *
     * @return runtime 组件；不可为空，线程安全。
     */
    public ZeroRuntimeComponents components() {
        return components;
    }

    /**
     * 返回 Kafka RPC lifecycle adapter。
     *
     * @return Adapter；为空表示未启用，线程安全。
     */
    public Optional<KafkaRpcLifecycleAdapter> kafkaRpcAdapter() {
        return Optional.ofNullable(kafkaRpcAdapter);
    }

    /**
     * 返回 MongoDB data adapter。
     *
     * @return Adapter；为空表示未启用，线程安全。
     */
    public Optional<MongoDataAdapter> mongoDataAdapter() {
        return Optional.ofNullable(mongoDataAdapter);
    }

    /**
     * 返回 MongoDB client。
     *
     * @return client；为空表示未启用，线程安全。
     */
    public Optional<MongoClient> mongoClient() {
        return Optional.ofNullable(mongoClient);
    }

    /**
     * 返回 Redis data adapter。
     *
     * @return Adapter；为空表示未启用，线程安全。
     */
    public Optional<RedisDataAdapter> redisDataAdapter() {
        return Optional.ofNullable(redisDataAdapter);
    }

    /**
     * 返回 Redis client。
     *
     * @return client；为空表示 Redis data/cache 均未启用，线程安全。
     */
    public Optional<RedisClient> redisClient() {
        return Optional.ofNullable(redisClient);
    }

    /**
     * 返回 PostgreSQL data adapter。
     *
     * @return Adapter；为空表示未启用，线程安全。
     */
    public Optional<PostgresqlDataAdapter> postgresqlDataAdapter() {
        return Optional.ofNullable(postgresqlDataAdapter);
    }

    /**
     * 返回服务发现组件。
     *
     * @return 服务发现；为空表示未启用 Nacos，线程安全。
     */
    public Optional<ServiceDiscovery> serviceDiscovery() {
        return Optional.ofNullable(serviceDiscovery);
    }

    /**
     * 返回 RPC 服务实例解析器。
     *
     * @return resolver；为空表示未启用服务发现协作，线程安全。
     */
    public Optional<RpcServiceResolver> rpcServiceResolver() {
        return Optional.ofNullable(rpcServiceResolver);
    }

    /**
     * 返回当前脱敏装配诊断报告。
     *
     * @return 诊断报告；不可为空，线程安全。
     */
    public ZeroProductionAssemblyReport report() {
        Map<String, ZeroProductionAdapterStatus> statuses = new LinkedHashMap<>();
        for (ProductionAdapterDiagnostic diagnostic : diagnostics) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            statuses.put(status.adapterName(), status);
        }
        return new ZeroProductionAssemblyReport(
                profile,
                components.config().getOrDefault(
                        group.zn.zero.starter.ZeroRuntimeConfigKeys.ZERO_NAME,
                        group.zn.zero.starter.ZeroRuntimeConfigKeys.DEFAULT_NAME),
                components.assemblyReport(),
                statuses,
                components.assemblyReport().lifecycleComponentTypes(),
                List.of());
    }

    /**
     * 在基类幂等短路前占用唯一启动机会。
     *
     * <p>线程安全：由基类在 start 实例监视器内调用。该方法不创建资源；已经启动、启动失败或请求关闭后，
     * 再次启动会以安全 ErrorCode 拒绝。</p>
     *
     * @throws ProductionAdapterException runtime 已被启动或关闭时抛出。
     */
    @Override
    protected void beforeStartRequest() {
        if (startClaimed || closeRequested) {
            throw ProductionAdapterFailures.failure(
                    "production-runtime",
                    ProductionAdapterFailurePhase.STARTUP,
                    ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED,
                    ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED.message());
        }
    }

    /**
     * 启动生产 runtime，并占用唯一启动机会。
     */
    @Override
    protected void doStart() {
        startClaimed = true;
        try {
            components.start();
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    "production-runtime",
                    ProductionAdapterFailurePhase.STARTUP,
                    ProductionAdapterErrorCode.STARTUP_FAILED,
                    ProductionAdapterErrorCode.STARTUP_FAILED.message(),
                    failure);
            closeBuildResources(
                    safeFailure,
                    ProductionAdapterFailurePhase.ROLLBACK,
                    ProductionAdapterErrorCode.ROLLBACK_FAILED);
            throw safeFailure;
        }
    }

    /**
     * 停止生产 runtime。
     */
    @Override
    protected void doStop() {
        closeRequested = true;
        ProductionAdapterException safeFailure = null;
        try {
            components.stop();
        } catch (RuntimeException | Error failure) {
            safeFailure = ProductionAdapterFailures.sanitize(
                    "production-runtime",
                    ProductionAdapterFailurePhase.CLOSE,
                    ProductionAdapterErrorCode.CLOSE_FAILED,
                    ProductionAdapterErrorCode.CLOSE_FAILED.message(),
                    failure);
        }
        safeFailure = closeBuildResources(
                safeFailure,
                ProductionAdapterFailurePhase.CLOSE,
                ProductionAdapterErrorCode.CLOSE_FAILED);
        if (safeFailure != null) {
            throw safeFailure;
        }
    }

    /**
     * 关闭 runtime；如果尚未 start，也会关闭 build 阶段创建的 client。
     *
     * <p>线程安全：与当前实例的 start/stop 串行化。调用会永久拒绝后续启动；运行中先停止全部已启动组件，
     * 否则直接逆序关闭 build 资源。成功关闭项保持幂等，失败项允许后续 close 重试；不修改业务数据。</p>
     *
     * @throws ProductionAdapterException 停止或资源关闭失败时抛出固定安全异常，后续失败以安全 suppressed 聚合。
     */
    @Override
    public synchronized void close() {
        closeRequested = true;
        if (running()) {
            stop();
            return;
        }
        ProductionAdapterException failure = closeBuildResources(
                null,
                ProductionAdapterFailurePhase.CLOSE,
                ProductionAdapterErrorCode.CLOSE_FAILED);
        if (failure != null) {
            throw failure;
        }
    }

    private ProductionAdapterException closeBuildResources(
            final ProductionAdapterException originalFailure,
            final ProductionAdapterFailurePhase failurePhase,
            final ProductionAdapterErrorCode errorCode) {
        synchronized (buildResourceCloseMonitor) {
            ProductionAdapterException failure = originalFailure;
            for (int index = buildCloseables.size() - 1; index >= 0; index--) {
                if (buildResourceClosed[index]) {
                    continue;
                }
                try {
                    buildCloseables.get(index).close();
                    buildResourceClosed[index] = true;
                } catch (Throwable closeFailure) {
                    ProductionAdapterException safeCloseFailure = ProductionAdapterFailures.reclassify(
                            "production-resource",
                            failurePhase,
                            errorCode,
                            errorCode.message(),
                            closeFailure);
                    if (failure == null) {
                        failure = safeCloseFailure;
                    } else {
                        failure.addSuppressed(safeCloseFailure);
                    }
                }
            }
            return failure;
        }
    }

    /**
     * production runtime 中创建出的 Adapter 实例集合。
     *
     * @param kafkaRpcAdapter Kafka RPC lifecycle adapter。
     * @param mongoDataAdapter MongoDB data adapter。
     * @param mongoClient MongoDB client。
     * @param redisDataAdapter Redis data adapter。
     * @param redisClient Redis client。
     * @param postgresqlDataAdapter PostgreSQL data adapter。
     * @param serviceDiscovery 服务发现组件。
     * @param rpcServiceResolver RPC 服务实例解析器。
     * @author zn
     */
    record RuntimeAdapters(
            KafkaRpcLifecycleAdapter kafkaRpcAdapter,
            MongoDataAdapter mongoDataAdapter,
            MongoClient mongoClient,
            RedisDataAdapter redisDataAdapter,
            RedisClient redisClient,
            PostgresqlDataAdapter postgresqlDataAdapter,
            ServiceDiscovery serviceDiscovery,
            RpcServiceResolver rpcServiceResolver) {
    }
}
