package group.zn.zero.starter;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.SystemLoggerLogSink;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ZeroConfigSource;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * 无 Docker、无外部连接的 Local Starter 薄入口。
 *
 * @author zn
 */
public final class LocalRuntime {

    private LocalRuntime() {
    }

    public static GameRuntime create() {
        return builder().build();
    }

    public static GameRuntime create(final ZeroConfig config) {
        return builder(config).build();
    }

    public static GameRuntime create(final ZeroConfig config, final LogSink terminalLogSink) {
        return builder(config, terminalLogSink).build();
    }

    public static GameRuntime create(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        return builder(config, terminalLogSink, executors).build();
    }

    public static LocalRuntimeBuilder builder() {
        return builder(ZeroConfigLoader.loadStandard(defaults()));
    }

    public static LocalRuntimeBuilder builder(final ZeroConfig config) {
        return new LocalRuntimeBuilder(
                mergeDefaults(config),
                () -> SystemLoggerLogSink.named(ZeroServerApplication.class.getName()),
                ZeroRuntimeExecutors.direct(), LocalRuntimePresets.local(), RuntimeProfile.local());
    }

    public static LocalRuntimeBuilder builder(final ZeroConfig config, final LogSink terminalLogSink) {
        return builder(config, terminalLogSink, ZeroRuntimeExecutors.direct());
    }

    public static LocalRuntimeBuilder builder(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        ZeroConfig merged = mergeDefaults(config);
        LogSink sink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        return new LocalRuntimeBuilder(
                merged,
                () -> sink,
                Objects.requireNonNull(executors, "executors"),
                LocalRuntimePresets.local(),
                RuntimeProfile.local());
    }

    public static LocalRuntimeBuilder minimalBuilder(
            final ZeroConfig config,
            final LogSink terminalLogSink,
            final ZeroRuntimeExecutors executors) {
        ZeroConfig merged = mergeDefaults(config);
        LogSink sink = Objects.requireNonNull(terminalLogSink, "terminalLogSink");
        return new LocalRuntimeBuilder(
                merged,
                () -> sink,
                Objects.requireNonNull(executors, "executors"),
                LocalRuntimePresets.minimal(),
                RuntimeProfile.minimal());
    }

    public static RuntimeCapabilityModel capabilityModel() {
        return StandardRuntimeCapabilityModel.instance();
    }

    /** Full local convenience module for an explicit composition root. */
    public static RuntimeModule module(
            final ZeroConfig config, final LogSink sink, final ZeroRuntimeExecutors executors) {
        LogSink checked = Objects.requireNonNull(sink, "sink");
        return module(config, () -> checked, executors);
    }

    /** Full local module with terminal sink creation deferred until its provider is selected. */
    public static RuntimeModule module(
            final ZeroConfig config, final Supplier<? extends LogSink> sink, final ZeroRuntimeExecutors executors) {
        ZeroConfig merged = mergeDefaults(config);
        return new RuntimeModule("zero.local", LocalRuntimeProviders.defaults(merged, sink, executors),
                LocalRuntimePresets.local(), List.of(new ZeroConfigSource("zero.local", merged)));
    }

    public static Map<String, String> defaults() {
        return Map.of(
                ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL,
                ZeroRuntimeConfigKeys.ZERO_NAME, ZeroRuntimeConfigKeys.DEFAULT_NAME);
    }

    static ZeroConfig mergeDefaults(final ZeroConfig config) {
        Map<String, String> values = new LinkedHashMap<>(defaults());
        values.putAll(Objects.requireNonNull(config, "config").asMap());
        return new MapZeroConfig(values);
    }

}
