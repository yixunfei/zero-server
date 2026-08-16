package group.zn.zero.starter;

import group.zn.zero.actor.scheduler.ExecutorActorScheduler;
import group.zn.zero.cache.CachePolicy;
import group.zn.zero.cache.LayeredCacheService;
import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.data.persistence.DefaultPersistenceManager;
import group.zn.zero.event.bus.InMemoryEventBus;
import group.zn.zero.event.deadletter.InMemoryDeadLetterSink;
import group.zn.zero.log.LogAppender;
import group.zn.zero.log.LogSink;
import group.zn.zero.monitor.MonitorRuntime;
import group.zn.zero.protocol.registry.InMemoryProtocolRegistry;
import group.zn.zero.rpc.local.InMemoryRpcTransport;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Local Starter 内置 provider 集合。 */
final class LocalRuntimeProviders {

    private static final ConfigKey<String> MODE = ConfigKey.string(
                    StandardRuntimeCapabilityModel.LOCAL_CONFIG, ZeroRuntimeConfigKeys.ZERO_MODE)
            .defaultValue(ZeroRuntimeConfigKeys.MODE_LOCAL)
            .validate(value -> !value.isBlank(), "non-blank")
            .build();

    private static final ConfigKey<String> NAME = ConfigKey.string(
                    StandardRuntimeCapabilityModel.LOCAL_CONFIG, ZeroRuntimeConfigKeys.ZERO_NAME)
            .defaultValue(ZeroRuntimeConfigKeys.DEFAULT_NAME)
            .validate(value -> !value.isBlank(), "non-blank")
            .build();

    private LocalRuntimeProviders() {
    }

    static List<RuntimeComponentProvider> defaults(
            final ZeroConfig inputConfig,
            final LogSink terminalLogSink,
            final LogAppender logAppender,
            final ZeroRuntimeExecutors executors) {
        List<RuntimeComponentProvider> providers = new ArrayList<>();
        providers.add(config(inputConfig));
        providers.add(executors(executors));
        providers.add(value(
                StandardRuntimeCapabilityModel.LOCAL_LOG_SINK,
                LocalRuntimeCapabilities.TERMINAL_LOG_SINK,
                terminalLogSink,
                ComponentKind.LOCAL));
        providers.add(logAppender(logAppender));
        providers.add(deadLetter());
        providers.add(eventBus());
        providers.add(actorScheduler());
        providers.add(value(
                StandardRuntimeCapabilityModel.LOCAL_PROTOCOL,
                LocalRuntimeCapabilities.PROTOCOL_REGISTRY,
                new InMemoryProtocolRegistry(),
                ComponentKind.LOCAL));
        providers.add(rpc());
        providers.add(persistence());
        providers.add(value(
                StandardRuntimeCapabilityModel.LOCAL_CACHE,
                LocalRuntimeCapabilities.CACHE_SERVICE,
                new LayeredCacheService<>(CachePolicy.defaults()),
                ComponentKind.LOCAL));
        providers.add(value(
                StandardRuntimeCapabilityModel.LOCAL_MONITOR,
                LocalRuntimeCapabilities.MONITOR_RUNTIME,
                MonitorRuntime.createDefault(),
                ComponentKind.LOCAL));
        return List.copyOf(providers);
    }

    static <T> RuntimeComponentProvider value(
            final ComponentId componentId,
            final ComponentKey<T> key,
            final T value,
            final ComponentKind kind) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(componentId)
                .provide(key)
                .kind(kind)
                .build();
        return provider(descriptor, context -> {
            ComponentContribution.Builder contribution = ComponentContribution.builder().bind(key, value);
            if (value instanceof Lifecycle lifecycle) {
                contribution.lifecycle(lifecycle);
            }
            return contribution.build();
        });
    }

    static RuntimeComponentProvider infrastructureLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(componentId)
                .provide(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES)
                .kind(ComponentKind.BUSINESS)
                .build();
        return provider(descriptor, context -> ComponentContribution.builder()
                .contribute(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES, lifecycle)
                .lifecycle(lifecycle)
                .build());
    }

    static RuntimeComponentProvider applicationLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(componentId)
                .provide(LocalRuntimeCapabilities.APPLICATION_LIFECYCLES)
                .require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER)
                .kind(ComponentKind.BUSINESS)
                .build();
        return provider(descriptor, context -> {
            context.require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER);
            return ComponentContribution.builder()
                    .contribute(LocalRuntimeCapabilities.APPLICATION_LIFECYCLES, lifecycle)
                    .lifecycle(lifecycle)
                    .build();
        });
    }

    private static RuntimeComponentProvider config(final ZeroConfig inputConfig) {
        ConfigSchema schema = ConfigSchema.builder(StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                .add(MODE)
                .add(NAME)
                .build();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_CONFIG)
                .provide(LocalRuntimeCapabilities.CONFIG)
                .configSchema(schema)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> {
            Map<String, String> values = new LinkedHashMap<>(inputConfig.asMap());
            values.put(ZeroRuntimeConfigKeys.ZERO_MODE, context.config().require(MODE));
            values.put(ZeroRuntimeConfigKeys.ZERO_NAME, context.config().require(NAME));
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.CONFIG, new MapZeroConfig(values))
                    .build();
        });
    }

    private static RuntimeComponentProvider executors(final ZeroRuntimeExecutors executors) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_EXECUTORS)
                .provide(LocalRuntimeCapabilities.EXECUTORS)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> {
            context.resources().register(executors);
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.EXECUTORS, executors)
                    .build();
        });
    }

    private static RuntimeComponentProvider logAppender(final LogAppender logAppender) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(
                        StandardRuntimeCapabilityModel.LOCAL_LOG_APPENDER)
                .provide(LocalRuntimeCapabilities.LOG_APPENDER)
                .require(LocalRuntimeCapabilities.CONFIG)
                .require(LocalRuntimeCapabilities.TERMINAL_LOG_SINK)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> {
            context.require(LocalRuntimeCapabilities.CONFIG);
            context.require(LocalRuntimeCapabilities.TERMINAL_LOG_SINK);
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.LOG_APPENDER, logAppender)
                    .build();
        });
    }

    private static RuntimeComponentProvider deadLetter() {
        return value(
                StandardRuntimeCapabilityModel.LOCAL_DEAD_LETTER,
                LocalRuntimeCapabilities.DEAD_LETTER_SINK,
                new InMemoryDeadLetterSink(),
                ComponentKind.LOCAL);
    }

    private static RuntimeComponentProvider eventBus() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_EVENT_BUS)
                .provide(LocalRuntimeCapabilities.EVENT_BUS)
                .require(LocalRuntimeCapabilities.DEAD_LETTER_SINK)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> ComponentContribution.builder()
                .bind(LocalRuntimeCapabilities.EVENT_BUS, new InMemoryEventBus(
                        context.require(LocalRuntimeCapabilities.DEAD_LETTER_SINK)))
                .build());
    }

    private static RuntimeComponentProvider actorScheduler() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_ACTOR)
                .provide(LocalRuntimeCapabilities.ACTOR_SCHEDULER)
                .require(LocalRuntimeCapabilities.EXECUTORS)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> ComponentContribution.builder()
                .bind(LocalRuntimeCapabilities.ACTOR_SCHEDULER, new ExecutorActorScheduler(
                        context.require(LocalRuntimeCapabilities.EXECUTORS).actorExecutor()))
                .build());
    }

    private static RuntimeComponentProvider rpc() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(StandardRuntimeCapabilityModel.LOCAL_RPC)
                .provide(LocalRuntimeCapabilities.RPC_TRANSPORT)
                .provide(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> {
            InMemoryRpcTransport transport = new InMemoryRpcTransport();
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.RPC_TRANSPORT, transport)
                    .bind(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY, transport)
                    .build();
        });
    }

    private static RuntimeComponentProvider persistence() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(
                        StandardRuntimeCapabilityModel.LOCAL_PERSISTENCE)
                .provide(LocalRuntimeCapabilities.PERSISTENCE_MANAGER)
                .optional(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES)
                .kind(ComponentKind.LOCAL)
                .build();
        return provider(descriptor, context -> {
            context.requireAll(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES);
            DefaultPersistenceManager persistence = new DefaultPersistenceManager();
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.PERSISTENCE_MANAGER, persistence)
                    .lifecycle(persistence)
                    .build();
        });
    }

    private static RuntimeComponentProvider provider(
            final ComponentDescriptor descriptor,
            final ProviderFactory factory) {
        return new DefaultProvider(descriptor, factory);
    }

    @FunctionalInterface
    private interface ProviderFactory {
        ComponentContribution create(ComponentCreationContext context) throws Exception;
    }

    private record DefaultProvider(
            ComponentDescriptor descriptor,
            ProviderFactory factory) implements RuntimeComponentProvider {

        private DefaultProvider {
            Objects.requireNonNull(descriptor, "descriptor");
            Objects.requireNonNull(factory, "factory");
        }

        @Override
        public ComponentContribution create(final ComponentCreationContext context) throws Exception {
            return factory.create(Objects.requireNonNull(context, "context"));
        }
    }
}
