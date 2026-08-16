package group.zn.zero.starter;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.config.ZeroConfigLoader;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.SystemLoggerLogSink;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.capability.RuntimeCapabilityModel;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

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
        return builder(
                config,
                SystemLoggerLogSink.named(ZeroServerApplication.class.getName()),
                ZeroRuntimeExecutors.direct());
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
        LogAppender appender = logAppender(merged, sink);
        return new LocalRuntimeBuilder(
                merged,
                sink,
                appender,
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
                sink,
                logAppender(merged, sink),
                Objects.requireNonNull(executors, "executors"),
                LocalRuntimePresets.minimal(),
                RuntimeProfile.minimal());
    }

    public static RuntimeCapabilityModel capabilityModel() {
        return StandardRuntimeCapabilityModel.instance();
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

    private static LogAppender logAppender(final ZeroConfig config, final LogSink terminalLogSink) {
        String runtimeMode = config.getOrDefault(
                ZeroRuntimeConfigKeys.ZERO_MODE,
                ZeroRuntimeConfigKeys.MODE_LOCAL);
        return new LogPipeline(
                List.of(record -> record.withField("runtimeMode", runtimeMode)),
                terminalLogSink);
    }
}
