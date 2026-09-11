package group.zn.zero.runtime.net;

import group.zn.zero.net.lifecycle.NetworkRateLimiter;
import group.zn.zero.net.lifecycle.ProductionNetworkConfig;
import group.zn.zero.net.lifecycle.ProductionNetworkLifecycle;
import group.zn.zero.net.lifecycle.ProductionNetworkPolicy;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.log.LogRuntime;
import group.zn.zero.runtime.monitor.MonitorRuntimeComponent;
import group.zn.zero.runtime.net.NetworkRuntime;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** 显式 policy 驱动、无外部连接副作用的 production network runtime provider。 */
final class ProductionNetworkProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_NETWORK_LIFECYCLE;

    private static final Set<ConfigSourceKind> SOURCES = Set.of(ConfigSourceKind.PROGRAMMATIC);
    private static final ConfigKey<String> LISTENER = string(
            ZeroProductionRuntimeConfigKeys.NETWORK_LISTENER,
            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_LISTENER);
    private static final ConfigKey<Integer> HANDSHAKE_TIMEOUT_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_HANDSHAKE_TIMEOUT_MILLIS,
            millis(ProductionNetworkConfig.DEFAULT_HANDSHAKE_TIMEOUT));
    private static final ConfigKey<Integer> AUTHENTICATION_TIMEOUT_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_AUTHENTICATION_TIMEOUT_MILLIS,
            millis(ProductionNetworkConfig.DEFAULT_AUTHENTICATION_TIMEOUT));
    private static final ConfigKey<Integer> HEARTBEAT_INTERVAL_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_HEARTBEAT_INTERVAL_MILLIS,
            millis(ProductionNetworkConfig.DEFAULT_HEARTBEAT_INTERVAL));
    private static final ConfigKey<Integer> ALLOWED_MISSED_HEARTBEATS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_ALLOWED_MISSED_HEARTBEATS,
            ProductionNetworkConfig.DEFAULT_ALLOWED_MISSED_HEARTBEATS);
    private static final ConfigKey<Integer> RECONNECT_WINDOW_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_RECONNECT_WINDOW_MILLIS,
            millis(ProductionNetworkConfig.DEFAULT_RECONNECT_WINDOW));
    private static final ConfigKey<Integer> MAX_INBOUND_FRAMES = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_MAX_INBOUND_FRAMES,
            ProductionNetworkConfig.DEFAULT_MAX_INBOUND_FRAMES);
    private static final ConfigKey<Integer> PER_IP_PERMITS_PER_SECOND = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_PER_IP_PERMITS_PER_SECOND,
            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_PER_IP_PERMITS_PER_SECOND);
    private static final ConfigKey<Integer> PER_IP_BURST_CAPACITY = positiveInteger(
            ZeroProductionRuntimeConfigKeys.NETWORK_PER_IP_BURST_CAPACITY,
            ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_PER_IP_BURST_CAPACITY);
    private static final ConfigKey<Integer> RATE_LIMIT_SLOTS = ConfigKey.integer(
                    ID, ZeroProductionRuntimeConfigKeys.NETWORK_RATE_LIMIT_SLOTS)
            .acceptedSources(SOURCES)
            .alias(ConfigSourceKind.PROGRAMMATIC, ProductionNetworkSettings.typedAlias(
                    ZeroProductionRuntimeConfigKeys.NETWORK_RATE_LIMIT_SLOTS))
            .defaultValue(ZeroProductionRuntimeConfigKeys.DEFAULT_NETWORK_RATE_LIMIT_SLOTS)
            .validate(ProductionIpConnectionRateLimiter::validSlots, "bounded-power-of-two")
            .build();
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID)
            .add(LISTENER)
            .add(HANDSHAKE_TIMEOUT_MILLIS)
            .add(AUTHENTICATION_TIMEOUT_MILLIS)
            .add(HEARTBEAT_INTERVAL_MILLIS)
            .add(ALLOWED_MISSED_HEARTBEATS)
            .add(RECONNECT_WINDOW_MILLIS)
            .add(MAX_INBOUND_FRAMES)
            .add(PER_IP_PERMITS_PER_SECOND)
            .add(PER_IP_BURST_CAPACITY)
            .add(RATE_LIMIT_SLOTS)
            .build();

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(NetworkRuntime.NETWORK_LIFECYCLE)
            .require(RuntimeBasics.CONFIG)
            .require(RuntimeBasics.EXECUTORS)
            .require(LogRuntime.LOG_APPENDER)
            .require(MonitorRuntimeComponent.MONITOR_RUNTIME)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.FOUNDATION)
            .build();
    private final ProductionNetworkPolicy policy;
    private final NetworkRateLimiter customRateLimiter;
    private final List<ConfigSource> configSources;
    private final ProductionAdapterDiagnostic diagnostic = new ProductionAdapterDiagnostic(
            ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE, ZeroProductionAdapterState.ENABLED,
            List.of(), List.of(), List.of(), List.of(), List.of(ProductionNetworkLifecycle.class.getName()));

    private ProductionNetworkProvider(
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter customRateLimiter,
            final List<ConfigSource> configSources) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.customRateLimiter = customRateLimiter;
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionNetworkPolicy policy,
            final NetworkRateLimiter customRateLimiter) {
        ProductionConfigResolver checkedResolver = Objects.requireNonNull(resolver, "resolver");
        if (!checkedResolver.enabled(ZeroProductionRuntimeConfigKeys.NETWORK_LIFECYCLE_ENABLED)) {
            return Resolution.disabled();
        }
        if (policy == null) {
            throw invalidSelection("builder.networkPolicy");
        }
        try {
            ProductionNetworkSettings settings = ProductionNetworkSettings.resolve(
                    checkedResolver, customRateLimiter == null);
            return Resolution.enabled(new ProductionNetworkProvider(
                    policy, customRateLimiter, settings.configSources()));
        } catch (ProductionNetworkSettings.InvalidSettingException failure) {
            throw ProductionAdapterFailures.invalidConfig(
                    ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    failure.logicalKey());
        }
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        ZeroRuntimeExecutors executors = checked.require(RuntimeBasics.EXECUTORS);
        if (executors.remoteIoMayInline()) {
            ProductionAdapterException failure = ProductionAdapterFailures.invalidConfig(ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION, "zero.executors.remoteIo");
            diagnostic.fail(failure);
            throw failure;
        }
        ComponentConfig config = checked.config();
        NetworkRateLimiter rateLimiter = customRateLimiter == null
                ? defaultRateLimiter(config)
                : customRateLimiter;
        ProductionNetworkLifecycle lifecycle = new ProductionNetworkLifecycle(
                networkConfig(config),
                policy,
                rateLimiter,
                new ProductionNetworkTelemetryObserver(
                        checked.require(LogRuntime.LOG_APPENDER),
                        checked.require(MonitorRuntimeComponent.MONITOR_RUNTIME).registry()),
                executors.remoteIoExecutor(),
                executors.backgroundExecutor());
        return ComponentContribution.builder()
                .bind(NetworkRuntime.NETWORK_LIFECYCLE, lifecycle)
                .build();
    }

    List<ConfigSource> configSources() {
        return configSources;
    }

    ProductionAdapterDiagnostic diagnostic() {
        return diagnostic;
    }

    private ProductionNetworkConfig networkConfig(final ComponentConfig config) {
        return new ProductionNetworkConfig(
                config.require(LISTENER),
                Duration.ofMillis(config.require(HANDSHAKE_TIMEOUT_MILLIS)),
                Duration.ofMillis(config.require(AUTHENTICATION_TIMEOUT_MILLIS)),
                Duration.ofMillis(config.require(HEARTBEAT_INTERVAL_MILLIS)),
                config.require(ALLOWED_MISSED_HEARTBEATS),
                Duration.ofMillis(config.require(RECONNECT_WINDOW_MILLIS)),
                config.require(MAX_INBOUND_FRAMES));
    }

    private NetworkRateLimiter defaultRateLimiter(final ComponentConfig config) {
        return new ProductionIpConnectionRateLimiter(
                config.require(PER_IP_PERMITS_PER_SECOND),
                config.require(PER_IP_BURST_CAPACITY),
                config.require(RATE_LIMIT_SLOTS));
    }

    private static ConfigKey<String> string(final String logicalName, final String defaultValue) {
        return ConfigKey.string(ID, logicalName)
                .acceptedSources(SOURCES)
                .alias(ConfigSourceKind.PROGRAMMATIC, ProductionNetworkSettings.typedAlias(logicalName))
                .defaultValue(defaultValue)
                .validate(ProductionNetworkProvider::validListener, "low-cardinality-name")
                .build();
    }

    private static ConfigKey<Integer> positiveInteger(final String logicalName, final int defaultValue) {
        return ConfigKey.integer(ID, logicalName)
                .acceptedSources(SOURCES)
                .alias(ConfigSourceKind.PROGRAMMATIC, ProductionNetworkSettings.typedAlias(logicalName))
                .defaultValue(defaultValue)
                .validate(value -> value > 0, "positive")
                .build();
    }

    private static boolean validListener(final String value) {
        try {
            ProductionNetworkConfig.defaults(value);
            return true;
        } catch (RuntimeException failure) {
            return false;
        }
    }

    private static int millis(final Duration duration) {
        return Math.toIntExact(duration.toMillis());
    }

    private static ProductionAdapterException invalidSelection(final String key) {
        return ProductionAdapterFailures.invalidConfig(
                ProductionAdapterNames.ADAPTER_NETWORK_LIFECYCLE,
                ProductionAdapterFailurePhase.CONFIG_SELECTION,
                key);
    }

    /** Network provider 的显式选择结果。 */
    record Resolution(boolean enabled, ProductionNetworkProvider provider) {

        Resolution {
            if (enabled != (provider != null)) {
                throw new IllegalArgumentException("enabled network resolution must contain exactly one provider");
            }
        }

        static Resolution enabled(final ProductionNetworkProvider provider) {
            return new Resolution(true, Objects.requireNonNull(provider, "provider"));
        }

        static Resolution disabled() {
            return new Resolution(false, null);
        }
    }
}
