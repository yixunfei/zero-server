package group.zn.zero.starter;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.cache.CacheService;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.data.persistence.PersistenceManager;
import group.zn.zero.event.bus.EventBus;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.registry.ProtocolRegistry;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * starter 运行时组件门面。
 *
 * <p>该门面只暴露阶段二已经稳定的抽象类型，避免业务层直接依赖具体 Adapter。
 * 真实中间件 Adapter 由后续 opt-in 工厂或业务项目显式注入。
 *
 * @author zn
 */
public final class ZeroRuntimeComponents extends AbstractLifecycle {

    /**
     * 框架配置。
     */
    private final ZeroConfig config;

    /**
     * 事件总线。
     */
    private final EventBus eventBus;

    /**
     * 死信接收器。
     */
    private final DeadLetterSink deadLetterSink;

    /**
     * Actor 调度器。
     */
    private final ActorScheduler actorScheduler;

    /**
     * 协议注册表。
     */
    private final ProtocolRegistry protocolRegistry;

    /**
     * RPC 传输。
     */
    private final RpcTransport rpcTransport;

    /**
     * RPC handler 注册表。
     */
    private final RpcHandlerRegistry rpcHandlerRegistry;

    /**
     * 持久化管理器。
     */
    private final PersistenceManager persistenceManager;

    /**
     * 默认缓存服务。
     */
    private final CacheService<Object, Object> cacheService;

    /**
     * 经过框架安全边界的日志写入端口。
     */
    private final LogAppender logAppender;

    /**
     * 监控运行时。
     */
    private final MonitorRuntime monitorRuntime;

    /**
     * 执行器装配点。
     */
    private final ZeroRuntimeExecutors executors;

    /**
     * 需要统一启动和停止的生命周期组件。
     */
    private final List<Lifecycle> lifecycleComponents;

    /**
     * 创建 starter 运行时组件门面。
     *
     * @param config 框架配置；不可为空。
     * @param eventBus 事件总线；不可为空。
     * @param deadLetterSink 死信接收器；不可为空。
     * @param actorScheduler Actor 调度器；不可为空。
     * @param protocolRegistry 协议注册表；不可为空。
     * @param rpcTransport RPC 传输；不可为空。
     * @param rpcHandlerRegistry RPC handler 注册表；不可为空。
     * @param persistenceManager 持久化管理器；不可为空。
     * @param cacheService 缓存服务；不可为空。
     * @param logAppender 安全日志写入端口；不可为空。
     * @param monitorRuntime 监控运行时；不可为空。
     * @param executors 执行器装配点；不可为空。
     * @param lifecycleComponents 生命周期组件；不可为空；调用方传入后会复制。
     * @throws NullPointerException 当任一必要参数为空时抛出。
     */
    public ZeroRuntimeComponents(
            final ZeroConfig config,
            final EventBus eventBus,
            final DeadLetterSink deadLetterSink,
            final ActorScheduler actorScheduler,
            final ProtocolRegistry protocolRegistry,
            final RpcTransport rpcTransport,
            final RpcHandlerRegistry rpcHandlerRegistry,
            final PersistenceManager persistenceManager,
            final CacheService<Object, Object> cacheService,
            final LogAppender logAppender,
            final MonitorRuntime monitorRuntime,
            final ZeroRuntimeExecutors executors,
            final List<Lifecycle> lifecycleComponents) {
        this.config = Objects.requireNonNull(config, "config");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.deadLetterSink = Objects.requireNonNull(deadLetterSink, "deadLetterSink");
        this.actorScheduler = Objects.requireNonNull(actorScheduler, "actorScheduler");
        this.protocolRegistry = Objects.requireNonNull(protocolRegistry, "protocolRegistry");
        this.rpcTransport = Objects.requireNonNull(rpcTransport, "rpcTransport");
        this.rpcHandlerRegistry = Objects.requireNonNull(rpcHandlerRegistry, "rpcHandlerRegistry");
        this.persistenceManager = Objects.requireNonNull(persistenceManager, "persistenceManager");
        this.cacheService = Objects.requireNonNull(cacheService, "cacheService");
        this.logAppender = Objects.requireNonNull(logAppender, "logAppender");
        this.monitorRuntime = Objects.requireNonNull(monitorRuntime, "monitorRuntime");
        this.executors = Objects.requireNonNull(executors, "executors");
        this.lifecycleComponents = List.copyOf(Objects.requireNonNull(lifecycleComponents, "lifecycleComponents"));
        this.lifecycleComponents.forEach(component -> Objects.requireNonNull(component, "component"));
    }

    /**
     * 返回框架配置。
     *
     * @return 配置；不可为空；线程安全。
     */
    public ZeroConfig config() {
        return config;
    }

    /**
     * 返回事件总线。
     *
     * @return 事件总线；不可为空；线程安全性由实现声明。
     */
    public EventBus eventBus() {
        return eventBus;
    }

    /**
     * 返回死信接收器。
     *
     * @return 死信接收器；不可为空；线程安全性由实现声明。
     */
    public DeadLetterSink deadLetterSink() {
        return deadLetterSink;
    }

    /**
     * 返回 Actor 调度器。
     *
     * @return Actor 调度器；不可为空；线程安全性由实现声明。
     */
    public ActorScheduler actorScheduler() {
        return actorScheduler;
    }

    /**
     * 返回协议注册表。
     *
     * @return 协议注册表；不可为空；线程安全性由实现声明。
     */
    public ProtocolRegistry protocolRegistry() {
        return protocolRegistry;
    }

    /**
     * 返回 RPC 传输。
     *
     * @return RPC 传输；不可为空；线程安全性由实现声明。
     */
    public RpcTransport rpcTransport() {
        return rpcTransport;
    }

    /**
     * 返回 RPC handler 注册表。
     *
     * @return RPC handler 注册表；不可为空；线程安全性由实现声明。
     */
    public RpcHandlerRegistry rpcHandlerRegistry() {
        return rpcHandlerRegistry;
    }

    /**
     * 返回持久化管理器。
     *
     * @return 持久化管理器；不可为空；线程安全性由实现声明。
     */
    public PersistenceManager persistenceManager() {
        return persistenceManager;
    }

    /**
     * 返回默认缓存服务。
     *
     * @return 缓存服务；不可为空；线程安全性由实现声明。
     */
    public CacheService<Object, Object> cacheService() {
        return cacheService;
    }

    /**
     * 返回经过框架安全边界的日志写入端口。
     *
     * @return 安全日志写入端口；不可为空；线程安全性由实现声明。
     */
    public LogAppender logAppender() {
        return logAppender;
    }

    /**
     * 返回监控运行时。
     *
     * @return 监控运行时；不可为空；线程安全性由实现声明。
     */
    public MonitorRuntime monitorRuntime() {
        return monitorRuntime;
    }

    /**
     * 返回执行器装配点。
     *
     * @return 执行器装配点；不可为空；线程安全。
     */
    public ZeroRuntimeExecutors executors() {
        return executors;
    }

    /**
     * 返回生命周期组件快照。
     *
     * @return 不可变、有序、可能为空、线程安全的生命周期组件列表。
     */
    public List<Lifecycle> lifecycleComponents() {
        return lifecycleComponents;
    }

    /**
     * 返回当前组件装配诊断报告。
     *
     * <p>该方法只读取组件类型和生命周期顺序，不读取敏感配置值、不修改业务状态。
     *
     * @return 装配诊断报告；不可为空；线程安全。
     */
    public ZeroRuntimeAssemblyReport assemblyReport() {
        return ZeroRuntimeAssemblyReport.from(this);
    }

    /**
     * 启动所有生命周期组件。
     */
    @Override
    protected void doStart() {
        List<Lifecycle> started = new ArrayList<>();
        try {
            for (Lifecycle component : lifecycleComponents) {
                component.start();
                started.add(component);
            }
        } catch (RuntimeException | Error ex) {
            ZeroException primaryFailure = normalizeFailure(ex, "Lifecycle start failed");
            stopStarted(started, primaryFailure);
            closeExecutorsAfterStartFailure(primaryFailure);
            throw primaryFailure;
        }
    }

    /**
     * 按反向顺序停止生命周期组件和执行器。
     */
    @Override
    protected void doStop() {
        List<Lifecycle> reversed = new ArrayList<>(lifecycleComponents);
        Collections.reverse(reversed);
        ZeroException failure = null;
        for (Lifecycle component : reversed) {
            try {
                component.stop();
            } catch (RuntimeException | Error ex) {
                if (failure == null) {
                    failure = normalizeFailure(ex, "Lifecycle stop failed");
                } else if (failure != ex) {
                    failure.addSuppressed(ex);
                }
            }
        }
        try {
            executors.close();
        } catch (RuntimeException | Error ex) {
            if (failure == null) {
                failure = normalizeFailure(ex, "Lifecycle stop failed");
            } else if (failure != ex) {
                failure.addSuppressed(ex);
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    /**
     * 启动失败后按实际启动顺序的逆序回滚，并把每个回滚失败附加到原始启动失败。
     *
     * <pre>
     * 启动失败 -&gt; 最后成功组件 -&gt; ... -&gt; 最先成功组件 -&gt; 重新抛出启动失败
     *                   \-- 停止失败继续回滚，并按发生顺序写入 suppressed
     * </pre>
     *
     * @param started 已成功启动的组件；不为 {@code null}，有序且仅由当前启动线程访问。
     * @param startFailure 原始启动失败；不为 {@code null}，本方法仅追加 suppressed 异常。
     */
    private void stopStarted(final List<Lifecycle> started, final ZeroException startFailure) {
        for (int index = started.size() - 1; index >= 0; index--) {
            try {
                started.get(index).stop();
            } catch (RuntimeException | Error stopFailure) {
                if (stopFailure != startFailure) {
                    startFailure.addSuppressed(stopFailure);
                }
            }
        }
    }

    /**
     * 启动失败后关闭 runtime 拥有的执行器，并把关闭失败保留为启动主异常的 suppressed。
     *
     * @param startFailure 原始启动失败；不为 {@code null}，本方法仅追加 suppressed 异常。
     */
    private void closeExecutorsAfterStartFailure(final ZeroException startFailure) {
        try {
            executors.close();
        } catch (RuntimeException | Error closeFailure) {
            if (closeFailure != startFailure) {
                startFailure.addSuppressed(closeFailure);
            }
        }
    }

    /**
     * 把生命周期边界的 raw 失败规范为统一主异常，使框架实际 cleanup 失败可以直接、有序地作为
     * suppressed 附加，同时保留 raw 失败为 cause 供调用方诊断。
     *
     * @param failure 原始失败；不可为空。
     * @param defaultMessage 原始失败无消息时使用的固定消息；不可为空。
     * @return 统一主异常；原失败已经是 ZeroException 时保持同一对象，否则返回新 wrapper。
     */
    private ZeroException normalizeFailure(final Throwable failure, final String defaultMessage) {
        if (failure instanceof ZeroException zeroFailure) {
            return zeroFailure;
        }
        String message = failure.getMessage() == null ? defaultMessage : failure.getMessage();
        return ZeroException.of(SystemErrorCode.SYSTEM_ERROR, message, failure);
    }
}
