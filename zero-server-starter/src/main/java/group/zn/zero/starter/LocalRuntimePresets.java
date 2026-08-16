package group.zn.zero.starter;

import group.zn.zero.runtime.assembly.RuntimePreset;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;

/** Local Starter 的命名显式选择清单。 */
public final class LocalRuntimePresets {

    private static final RuntimePreset MINIMAL = presetBuilder(StandardRuntimeCapabilityModel.PROFILE_MINIMAL)
            .require(LocalRuntimeCapabilities.CONFIG)
            .require(LocalRuntimeCapabilities.EXECUTORS)
            .require(LocalRuntimeCapabilities.LOG_APPENDER)
            .require(LocalRuntimeCapabilities.MONITOR_RUNTIME)
            .build();

    private static final RuntimePreset LOCAL = presetBuilder(StandardRuntimeCapabilityModel.PROFILE_LOCAL)
            .require(LocalRuntimeCapabilities.CONFIG)
            .require(LocalRuntimeCapabilities.EXECUTORS)
            .require(LocalRuntimeCapabilities.LOG_APPENDER)
            .require(LocalRuntimeCapabilities.DEAD_LETTER_SINK)
            .require(LocalRuntimeCapabilities.EVENT_BUS)
            .require(LocalRuntimeCapabilities.ACTOR_SCHEDULER)
            .require(LocalRuntimeCapabilities.PROTOCOL_REGISTRY)
            .require(LocalRuntimeCapabilities.RPC_TRANSPORT)
            .require(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY)
            .require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER)
            .require(LocalRuntimeCapabilities.CACHE_SERVICE)
            .require(LocalRuntimeCapabilities.MONITOR_RUNTIME)
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
                .select(LocalRuntimeCapabilities.CONFIG, StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                .select(LocalRuntimeCapabilities.EXECUTORS, StandardRuntimeCapabilityModel.LOCAL_EXECUTORS)
                .select(LocalRuntimeCapabilities.TERMINAL_LOG_SINK, StandardRuntimeCapabilityModel.LOCAL_LOG_SINK)
                .select(LocalRuntimeCapabilities.LOG_APPENDER, StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER)
                .select(LocalRuntimeCapabilities.DEAD_LETTER_SINK,
                        StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER)
                .select(LocalRuntimeCapabilities.EVENT_BUS, StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS)
                .select(LocalRuntimeCapabilities.ACTOR_SCHEDULER, StandardRuntimeCapabilityModel.LOCAL_ACTOR)
                .select(LocalRuntimeCapabilities.PROTOCOL_REGISTRY, StandardRuntimeCapabilityModel.LOCAL_PROTOCOL)
                .select(LocalRuntimeCapabilities.RPC_TRANSPORT, StandardRuntimeCapabilityModel.LOCAL_RPC)
                .select(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY, StandardRuntimeCapabilityModel.LOCAL_RPC)
                .select(LocalRuntimeCapabilities.PERSISTENCE_MANAGER,
                        StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE)
                .select(LocalRuntimeCapabilities.CACHE_SERVICE, StandardRuntimeCapabilityModel.LOCAL_CACHE)
                .select(LocalRuntimeCapabilities.MONITOR_RUNTIME, StandardRuntimeCapabilityModel.LOCAL_MONITOR);
    }
}
