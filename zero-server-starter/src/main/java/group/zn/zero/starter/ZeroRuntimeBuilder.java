package group.zn.zero.starter;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.CacheService;
import group.zn.zero.cache.LayeredCacheService;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.data.persistence.PersistenceManager;
import group.zn.zero.event.bus.EventBus;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogSink;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.registry.InMemoryProtocolRegistry;
import group.zn.zero.protocol.registry.ProtocolRegistry;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * starter 显式运行时装配构建器。
 *
 * <p>该构建器用于在本地默认装配基础上覆盖单个组件槽位。构建器本身不是线程安全对象，
 * 应在启动前由单线程完成配置；构建出的 `ZeroRuntimeComponents` 线程安全性由各组件声明。
 *
 * @author zn
 */
public final class ZeroRuntimeBuilder {

    /**
     * 框架配置。
     */
    private ZeroConfig config;

    /**
     * 终端日志落地 SPI。
     */
    private LogSink terminalLogSink;

    /**
     * 延迟创建并冻结的安全日志写入端口。
     */
    private LogAppender logAppender;

    /**
     * 执行器装配点。
     */
    private ZeroRuntimeExecutors executors;

    /**
     * 死信接收器覆盖项。
     */
    private DeadLetterSink deadLetterSink;

    /**
     * 事件总线覆盖项。
     */
    private EventBus eventBus;

    /**
     * Actor 调度器覆盖项。
     */
    private ActorScheduler actorScheduler;

    /**
     * 协议注册表覆盖项。
     */
    private ProtocolRegistry protocolRegistry;

    /**
     * RPC 传输覆盖项。
     */
    private RpcTransport rpcTransport;

    /**
     * RPC handler 注册表覆盖项。
     */
    private RpcHandlerRegistry rpcHandlerRegistry;

    /**
     * 持久化管理器覆盖项。
     */
    private PersistenceManager persistenceManager;

    /**
     * 缓存服务覆盖项。
     */
    private CacheService<Object, Object> cacheService;

    /**
     * 监控运行时覆盖项。
     */
    private MonitorRuntime monitorRuntime;

    /**
     * 先于持久化和业务组件启动、后于它们停止的基础设施生命周期组件。
     */
    private final List<Lifecycle> infrastructureLifecycleComponents = new ArrayList<>();

    /**
     * 附加生命周期组件。
     */
    private final List<Lifecycle> additionalLifecycleComponents = new ArrayList<>();

    /**
     * 显式生命周期组件列表。
     */
    private List<Lifecycle> explicitLifecycleComponents;

    ZeroRuntimeBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        this.config = Objects.requireNonNull(config, "config");
        this.terminalLogSink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        this.executors = Objects.requireNonNull(executors, "executors");
    }

    /**
     * 覆盖框架配置。
     *
     * <p>该方法只更新装配输入，不启动组件、不修改业务状态。最终构建时仍会合并本地默认值。
     *
     * @param config 框架配置；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当配置为空时抛出。
     * @throws IllegalStateException 当安全日志端口已经初始化时抛出。
     */
    public ZeroRuntimeBuilder config(final ZeroConfig config) {
        Objects.requireNonNull(config, "config");
        requireMutableLogAssembly();
        this.config = config;
        return this;
    }

    /**
     * 覆盖终端日志落地 SPI。
     *
     * <p>该 SPI 会被带有不可关闭安全门的 {@link LogPipeline} 包装，不能作为业务日志入口。
     * 该方法只允许在安全日志端口首次取得前调用。</p>
     *
     * @param terminalLogSink 终端日志落地 SPI；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当终端日志 SPI 为空时抛出。
     * @throws IllegalStateException 当安全日志端口已经初始化时抛出。
     */
    public ZeroRuntimeBuilder terminalLogSink(final LogSink terminalLogSink) {
        Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        requireMutableLogAssembly();
        this.terminalLogSink = terminalLogSink;
        return this;
    }

    /**
     * 返回当前构建器唯一的安全日志写入端口。
     *
     * <p>首次调用时创建并冻结 {@link LogPipeline}，后续调用及 {@link #build()} 均复用同一实例。
     * 该方法不写日志、不执行 IO、不创建线程；调用后不能再替换配置或终端日志 SPI。</p>
     *
     * @return 安全日志写入端口；不可为空；线程安全性由处理器和终端 SPI 声明。
     */
    public LogAppender logAppender() {
        if (logAppender == null) {
            String runtimeMode = config.getOrDefault(
                    ZeroRuntimeConfigKeys.ZERO_MODE,
                    ZeroRuntimeConfigKeys.MODE_LOCAL);
            logAppender = new LogPipeline(
                    List.of(record -> record.withField("runtimeMode", runtimeMode)),
                    terminalLogSink);
        }
        return logAppender;
    }

    /**
     * 覆盖执行器装配点。
     *
     * @param executors 执行器装配点；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当执行器装配点为空时抛出。
     */
    public ZeroRuntimeBuilder executors(final ZeroRuntimeExecutors executors) {
        this.executors = Objects.requireNonNull(executors, "executors");
        return this;
    }

    /**
     * 覆盖死信接收器。
     *
     * <p>若未显式覆盖事件总线，默认事件总线会使用该死信接收器。
     *
     * @param deadLetterSink 死信接收器；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当死信接收器为空时抛出。
     */
    public ZeroRuntimeBuilder deadLetterSink(final DeadLetterSink deadLetterSink) {
        this.deadLetterSink = Objects.requireNonNull(deadLetterSink, "deadLetterSink");
        return this;
    }

    /**
     * 覆盖事件总线。
     *
     * @param eventBus 事件总线；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当事件总线为空时抛出。
     */
    public ZeroRuntimeBuilder eventBus(final EventBus eventBus) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        return this;
    }

    /**
     * 覆盖 Actor 调度器。
     *
     * @param actorScheduler Actor 调度器；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当 Actor 调度器为空时抛出。
     */
    public ZeroRuntimeBuilder actorScheduler(final ActorScheduler actorScheduler) {
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        return this;
    }

    /**
     * 覆盖协议注册表。
     *
     * @param protocolRegistry 协议注册表；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当协议注册表为空时抛出。
     */
    public ZeroRuntimeBuilder protocolRegistry(final ProtocolRegistry protocolRegistry) {
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry");
        return this;
    }

    /**
     * 覆盖 RPC 传输。
     *
     * <p>若该对象不能同时作为 `RpcHandlerRegistry`，调用方必须额外调用
     * {@link #rpcHandlerRegistry(RpcHandlerRegistry)}，否则构建阶段会抛出统一异常。
     *
     * @param rpcTransport RPC 传输；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当 RPC 传输为空时抛出。
     */
    public ZeroRuntimeBuilder rpcTransport(final RpcTransport rpcTransport) {
        this.rpcTransport = Objects.requireNonNull(rpcTransport, "rpcTransport");
        return this;
    }

    /**
     * 覆盖 RPC handler 注册表。
     *
     * <p>若该对象不能同时作为 `RpcTransport`，调用方必须额外调用
     * {@link #rpcTransport(RpcTransport)}，否则构建阶段会抛出统一异常。
     *
     * @param rpcHandlerRegistry RPC handler 注册表；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当 RPC handler 注册表为空时抛出。
     */
    public ZeroRuntimeBuilder rpcHandlerRegistry(final RpcHandlerRegistry rpcHandlerRegistry) {
        this.rpcHandlerRegistry = Objects.requireNonNull(rpcHandlerRegistry, "rpcHandlerRegistry");
        return this;
    }

    /**
     * 覆盖持久化管理器。
     *
     * @param persistenceManager 持久化管理器；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当持久化管理器为空时抛出。
     */
    public ZeroRuntimeBuilder persistenceManager(final PersistenceManager persistenceManager) {
        this.persistenceManager = Objects.requireNonNull(persistenceManager, "persistenceManager");
        return this;
    }

    /**
     * 覆盖缓存服务。
     *
     * @param cacheService 缓存服务；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当缓存服务为空时抛出。
     */
    public ZeroRuntimeBuilder cacheService(final CacheService<Object, Object> cacheService) {
        this.cacheService = Objects.requireNonNull(cacheService, "cacheService");
        return this;
    }

    /**
     * 覆盖监控运行时。
     *
     * @param monitorRuntime 监控运行时；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当监控运行时为空时抛出。
     */
    public ZeroRuntimeBuilder monitorRuntime(final MonitorRuntime monitorRuntime) {
        this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
        return this;
    }

    /**
     * 附加一个生命周期组件。
     *
     * <p>默认情况下 builder 会管理持久化管理器生命周期。该方法用于追加需要由 starter
     * 启动和停止的自定义组件。重复对象会按身份去重，避免同一组件被启动两次。
     *
     * @param component 生命周期组件；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当生命周期组件为空时抛出。
     */
    public ZeroRuntimeBuilder addLifecycleComponent(final Lifecycle component) {
        additionalLifecycleComponents.add(Objects.requireNonNull(component, "component"));
        return this;
    }

    /**
     * 追加一个基础设施生命周期组件。
     *
     * <p>基础设施按加入顺序先于持久化管理器和普通附加组件启动，并因 runtime 反向停止而后于
     * 它们停止。该包内装配点只供 Starter 工厂使用；重复对象在构建时按身份去重。调用本方法
     * 只修改未启动 builder 的装配状态，不启动组件、不修改业务数据，且非线程安全。</p>
     *
     * @param component 基础设施生命周期组件；不可为空。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当组件为空时抛出。
     */
    ZeroRuntimeBuilder addInfrastructureLifecycleComponent(final Lifecycle component) {
        infrastructureLifecycleComponents.add(Objects.requireNonNull(component, "component"));
        return this;
    }

    /**
     * 显式覆盖完整生命周期组件列表。
     *
     * <p>调用该方法后 builder 不再自动加入持久化管理器和附加组件，调用方需要自行保证启动
     * 与停止顺序。该方法用于高级装配场景。
     *
     * @param lifecycleComponents 生命周期组件列表；不可为空；调用后会复制。
     * @return 当前构建器；不可为空；非线程安全。
     * @throws NullPointerException 当列表或列表元素为空时抛出。
     */
    public ZeroRuntimeBuilder lifecycleComponents(final List<? extends Lifecycle> lifecycleComponents) {
        List<Lifecycle> copied = new ArrayList<>(Objects.requireNonNull(
                lifecycleComponents,
                "lifecycleComponents"));
        copied.forEach(component -> Objects.requireNonNull(component, "component"));
        this.explicitLifecycleComponents = List.copyOf(copied);
        return this;
    }

    /**
     * 构建运行时组件。
     *
     * <p>该方法只创建组件门面，不启动组件、不修改业务状态。返回的生命周期组件列表有序、
     * 不可变，启动由 `ZeroRuntimeComponents.start()` 或 `ZeroServerApplication.start()` 触发。
     *
     * @return 运行时组件；不可为空；未启动；线程安全性由内部组件声明。
     * @throws ZeroException 当 RPC 传输和 handler 注册表无法形成一致组合时抛出。
     */
    public ZeroRuntimeComponents build() {
        ZeroConfig currentConfig = ZeroRuntimeFactory.mergeLocalDefaults(config);
        DeadLetterSink currentDeadLetterSink = deadLetterSink == null
                ? new InMemoryDeadLetterSink()
                : deadLetterSink;
        EventBus currentEventBus = eventBus == null
                ? new InMemoryEventBus(currentDeadLetterSink)
                : eventBus;
        RpcPair rpcPair = resolveRpcPair();
        PersistenceManager currentPersistenceManager = persistenceManager == null
                ? new DefaultPersistenceManager()
                : persistenceManager;
        return new ZeroRuntimeComponents(
                currentConfig,
                currentEventBus,
                currentDeadLetterSink,
                actorScheduler == null ? new ExecutorActorScheduler(executors.actorExecutor()) : actorScheduler,
                protocolRegistry == null ? new InMemoryProtocolRegistry() : protocolRegistry,
                rpcPair.transport(),
                rpcPair.handlerRegistry(),
                currentPersistenceManager,
                cacheService == null ? new LayeredCacheService<>(CachePolicy.defaults()) : cacheService,
                logAppender(),
                monitorRuntime == null ? MonitorRuntime.createDefault() : monitorRuntime,
                executors,
                lifecycleComponents(currentPersistenceManager));
    }

    private void requireMutableLogAssembly() {
        if (logAppender != null) {
            throw new IllegalStateException("log appender has already been initialized");
        }
    }

    private RpcPair resolveRpcPair() {
        if (rpcTransport == null && rpcHandlerRegistry == null) {
            InMemoryRpcTransport localTransport = new InMemoryRpcTransport();
            return new RpcPair(localTransport, localTransport);
        }
        if (rpcTransport != null && rpcHandlerRegistry != null) {
            return new RpcPair(rpcTransport, rpcHandlerRegistry);
        }
        if (rpcTransport instanceof RpcHandlerRegistry registry) {
            return new RpcPair(rpcTransport, registry);
        }
        if (rpcHandlerRegistry instanceof RpcTransport transport) {
            return new RpcPair(transport, rpcHandlerRegistry);
        }
        throw ZeroException.of(
                SystemErrorCode.INVALID_ARGUMENT,
                "rpcTransport and rpcHandlerRegistry must be provided together "
                        + "unless one component implements both contracts",
                null);
    }

    private List<Lifecycle> lifecycleComponents(final PersistenceManager currentPersistenceManager) {
        if (explicitLifecycleComponents != null) {
            return explicitLifecycleComponents;
        }
        List<Lifecycle> ordered = new ArrayList<>();
        ordered.addAll(infrastructureLifecycleComponents);
        ordered.add(currentPersistenceManager);
        ordered.addAll(additionalLifecycleComponents);
        return identityDistinct(ordered);
    }

    private List<Lifecycle> identityDistinct(final List<Lifecycle> components) {
        Set<Lifecycle> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        List<Lifecycle> result = new ArrayList<>();
        for (Lifecycle component : components) {
            if (seen.add(component)) {
                result.add(component);
            }
        }
        return List.copyOf(result);
    }

    /**
     * RPC 传输和 handler 注册表组合。
     *
     * @param transport RPC 传输。
     * @param handlerRegistry RPC handler 注册表。
     * @author zn
     */
    private record RpcPair(RpcTransport transport, RpcHandlerRegistry handlerRegistry) {

        /**
         * 创建 RPC 组合。
         *
         * @throws NullPointerException 当任一组件为空时抛出。
         */
        private RpcPair {
            Objects.requireNonNull(transport, "transport");
            Objects.requireNonNull(handlerRegistry, "handlerRegistry");
        }
    }
}
