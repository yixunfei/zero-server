package group.zn.zero.starter;

import group.zn.zero.actor.scheduler.ActorScheduler;
import group.zn.zero.cache.CacheService;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.data.persistence.PersistenceManager;
import group.zn.zero.event.bus.EventBus;
import group.zn.zero.event.deadletter.DeadLetterSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogSink;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.registry.ProtocolRegistry;
import group.zn.zero.rpc.spi.RpcHandlerRegistry;
import group.zn.zero.rpc.spi.RpcTransport;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;

/**
 * Local Starter 对共享逻辑能力词汇的 typed key 绑定。
 *
 * @author zn
 */
public final class LocalRuntimeCapabilities {

    public static final ComponentKey<ZeroConfig> CONFIG = single(
            StandardRuntimeCapabilityModel.CONFIG, ZeroConfig.class);
    public static final ComponentKey<ZeroRuntimeExecutors> EXECUTORS = single(
            StandardRuntimeCapabilityModel.EXECUTORS, ZeroRuntimeExecutors.class);
    public static final ComponentKey<LogSink> TERMINAL_LOG_SINK = single(
            StandardRuntimeCapabilityModel.TERMINAL_LOG_SINK, LogSink.class);
    public static final ComponentKey<LogAppender> LOG_APPENDER = single(
            StandardRuntimeCapabilityModel.LOG_APPENDER, LogAppender.class);
    public static final ComponentKey<DeadLetterSink> DEAD_LETTER_SINK = single(
            StandardRuntimeCapabilityModel.DEAD_LETTER_SINK, DeadLetterSink.class);
    public static final ComponentKey<EventBus> EVENT_BUS = single(
            StandardRuntimeCapabilityModel.EVENT_BUS, EventBus.class);
    public static final ComponentKey<ActorScheduler> ACTOR_SCHEDULER = single(
            StandardRuntimeCapabilityModel.ACTOR_SCHEDULER, ActorScheduler.class);
    public static final ComponentKey<ProtocolRegistry> PROTOCOL_REGISTRY = single(
            StandardRuntimeCapabilityModel.PROTOCOL_REGISTRY, ProtocolRegistry.class);
    public static final ComponentKey<RpcTransport> RPC_TRANSPORT = single(
            StandardRuntimeCapabilityModel.RPC_TRANSPORT, RpcTransport.class);
    public static final ComponentKey<RpcHandlerRegistry> RPC_HANDLER_REGISTRY = single(
            StandardRuntimeCapabilityModel.RPC_HANDLER_REGISTRY, RpcHandlerRegistry.class);
    public static final ComponentKey<PersistenceManager> PERSISTENCE_MANAGER = single(
            StandardRuntimeCapabilityModel.PERSISTENCE_MANAGER, PersistenceManager.class);
    public static final ComponentKey<CacheService<Object, Object>> CACHE_SERVICE = single(
            StandardRuntimeCapabilityModel.CACHE_SERVICE, CacheService.class);
    public static final ComponentKey<MonitorRuntime> MONITOR_RUNTIME = single(
            StandardRuntimeCapabilityModel.MONITOR_RUNTIME, MonitorRuntime.class);
    public static final ComponentSetKey<Lifecycle> INFRASTRUCTURE_LIFECYCLES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.INFRASTRUCTURE_LIFECYCLES, Lifecycle.class);
    public static final ComponentSetKey<Lifecycle> APPLICATION_LIFECYCLES = ComponentSetKey.multiple(
            StandardRuntimeCapabilityModel.APPLICATION_LIFECYCLES, Lifecycle.class);

    private LocalRuntimeCapabilities() {
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static <T> ComponentKey<T> single(final String id, final Class<?> type) {
        return ComponentKey.single(id, (Class) type);
    }
}
