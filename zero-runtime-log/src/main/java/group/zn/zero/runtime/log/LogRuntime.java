package group.zn.zero.runtime.log;

import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogPipeline;
import group.zn.zero.log.LogSink;
import group.zn.zero.log.SystemLoggerLogSink;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeConfigKeys;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.function.Supplier;
import java.util.List;
import java.util.Objects;

/** Logging integration; the pipeline consumes the selected configuration and terminal sink. */
public final class LogRuntime {
    public static final ComponentKey<LogSink> TERMINAL_LOG_SINK = ComponentKey.single(
            StandardRuntimeCapabilityModel.TERMINAL_LOG_SINK, LogSink.class);
    public static final ComponentKey<LogAppender> LOG_APPENDER = ComponentKey.single(
            StandardRuntimeCapabilityModel.LOG_APPENDER, LogAppender.class);

    private LogRuntime() {
    }

    public static RuntimeModule module() {
        return module(() -> SystemLoggerLogSink.named("zero.runtime"));
    }

    public static RuntimeModule module(final Supplier<? extends LogSink> sink) {
        return RuntimeModule.of("zero.log", providers(sink), LOG_APPENDER);
    }

    public static List<RuntimeComponentProvider> providers(final Supplier<? extends LogSink> sink) {
        Objects.requireNonNull(sink, "sink");
        return List.of(
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_LOG_SINK)
                        .provide(TERMINAL_LOG_SINK).kind(ComponentKind.LOCAL).build(), context ->
                        ComponentContribution.builder().bind(TERMINAL_LOG_SINK, sink.get()).build()),
                RuntimeProviders.create(ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER)
                        .provide(LOG_APPENDER).require(RuntimeBasics.CONFIG).require(TERMINAL_LOG_SINK)
                        .kind(ComponentKind.LOCAL).build(), context -> {
                            String mode = context.require(RuntimeBasics.CONFIG).getOrDefault(
                                    ZeroRuntimeConfigKeys.ZERO_MODE, ZeroRuntimeConfigKeys.MODE_LOCAL);
                            return ComponentContribution.builder().bind(LOG_APPENDER, new LogPipeline(
                                    List.of(record -> record.withField("runtimeMode", mode)),
                                    context.require(TERMINAL_LOG_SINK))).build();
                        }));
    }
}
