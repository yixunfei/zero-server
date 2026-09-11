package group.zn.zero.starter;

import group.zn.zero.runtime.actor.ActorRuntime;
import group.zn.zero.runtime.assembly.RuntimePreset;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.cache.CacheRuntime;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.event.EventRuntime;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.protocol.ProtocolRuntime;
import group.zn.zero.runtime.rpc.RpcRuntime;

/** Local Starter 的命名显式选择清单。 */
public final class LocalRuntimePresets {

    private static final RuntimePreset MINIMAL = presetBuilder(StandardRuntimeCapabilityModel.PROFILE_MINIMAL)
            .require(RuntimeBasics.CONFIG)
            .require(RuntimeBasics.EXECUTORS)
            .require(LogRuntime.LOG_APPENDER)
            .require(MonitorRuntimeComponent.MONITOR_RUNTIME)
            .build();

    private static final RuntimePreset LOCAL = presetBuilder(StandardRuntimeCapabilityModel.PROFILE_LOCAL)
            .require(RuntimeBasics.CONFIG)
            .require(RuntimeBasics.EXECUTORS)
            .require(LogRuntime.LOG_APPENDER)
            .require(EventRuntime.DEAD_LETTER_SINK)
            .require(EventRuntime.EVENT_BUS)
            .require(ActorRuntime.ACTOR_SCHEDULER)
            .require(ProtocolRuntime.PROTOCOL_REGISTRY)
            .require(RpcRuntime.RPC_TRANSPORT)
            .require(RpcRuntime.RPC_HANDLER_REGISTRY)
            .require(DataRuntime.PERSISTENCE_MANAGER)
            .require(DataRuntime.REPOSITORY_SOURCES)
            .require(CacheRuntime.CACHE_SERVICE)
            .require(MonitorRuntimeComponent.MONITOR_RUNTIME)
            .build();

    private LocalRuntimePresets() {
    }

    public static RuntimePreset minimal() {
        return MINIMAL;
    }

    public static RuntimePreset local() {
        return LOCAL;
    }

    private static RuntimePreset.Builder presetBuilder(final String name) {
        return RuntimePreset.builder(name)
                .select(RuntimeBasics.CONFIG, StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                .select(RuntimeBasics.EXECUTORS, StandardRuntimeCapabilityModel.LOCAL_EXECUTORS)
                .select(LogRuntime.TERMINAL_LOG_SINK, StandardRuntimeCapabilityModel.LOCAL_LOG_SINK)
                .select(LogRuntime.LOG_APPENDER, StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER)
                .select(EventRuntime.DEAD_LETTER_SINK,
                        StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER)
                .select(EventRuntime.EVENT_BUS, StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS)
                .select(ActorRuntime.ACTOR_SCHEDULER, StandardRuntimeCapabilityModel.LOCAL_ACTOR)
                .select(ProtocolRuntime.PROTOCOL_REGISTRY, StandardRuntimeCapabilityModel.LOCAL_PROTOCOL)
                .select(RpcRuntime.RPC_TRANSPORT, StandardRuntimeCapabilityModel.LOCAL_RPC)
                .select(RpcRuntime.RPC_HANDLER_REGISTRY, StandardRuntimeCapabilityModel.LOCAL_RPC)
                .select(DataRuntime.PERSISTENCE_MANAGER,
                        StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE)
                .contribute(DataRuntime.REPOSITORY_SOURCES, StandardRuntimeCapabilityModel.LOCAL_REPOSITORIES)
                .select(CacheRuntime.CACHE_SERVICE, StandardRuntimeCapabilityModel.LOCAL_CACHE)
                .select(MonitorRuntimeComponent.MONITOR_RUNTIME, StandardRuntimeCapabilityModel.LOCAL_MONITOR);
    }
}
