package group.zn.zero.starter.production;

import group.zn.zero.log.LogAppender;
import group.zn.zero.rpc.kafka.KafkaRpcSettings;
import group.zn.zero.rpc.observer.RpcTransportObserver;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.starter.LocalRuntimeCapabilities;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** 显式选择、typed config 且强制 startup health 的 Kafka RPC runtime provider。 */
final class ProductionKafkaRpcProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_KAFKA_RPC;

    private static final Set<String> ALLOWED_CLIENT_PROPERTY_KEYS = Set.of("security.protocol");
    private static final List<String> ALLOWED_CLIENT_PROPERTY_PREFIXES = List.of("ssl.", "sasl.");
    private static final Set<ConfigSourceKind> DIRECT_SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY);
    private static final Set<ConfigSourceKind> BOOTSTRAP_SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY,
            ConfigSourceKind.ENVIRONMENT);

    private static final ConfigKey<String> BOOTSTRAP_SERVERS = sensitiveString(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
            BOOTSTRAP_SOURCES,
            ZeroProductionRuntimeConfigKeys.ENV_RPC_KAFKA_BOOTSTRAP_SERVERS);
    private static final ConfigKey<String> CLIENT_ID = sensitiveString(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID, DIRECT_SOURCES, null);
    private static final ConfigKey<String> CONSUMER_GROUP_ID = sensitiveString(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID, DIRECT_SOURCES, null);
    private static final ConfigKey<String> TOPIC_PREFIX = sensitiveString(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX, DIRECT_SOURCES, null);
    private static final ConfigKey<String> REPLY_TOPIC = sensitiveString(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC, DIRECT_SOURCES, null);
    private static final ConfigKey<Integer> PENDING_CAPACITY = positiveInteger(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_PENDING_CAPACITY, 4096);
    private static final ConfigKey<Integer> POLL_TIMEOUT_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_POLL_TIMEOUT_MILLIS, 100);
    private static final ConfigKey<Integer> CLOSE_TIMEOUT_MILLIS = positiveInteger(
            ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLOSE_TIMEOUT_MILLIS, 3000);
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID)
            .add(BOOTSTRAP_SERVERS)
            .add(CLIENT_ID)
            .add(CONSUMER_GROUP_ID)
            .add(TOPIC_PREFIX)
            .add(REPLY_TOPIC)
            .add(PENDING_CAPACITY)
            .add(POLL_TIMEOUT_MILLIS)
            .add(CLOSE_TIMEOUT_MILLIS)
            .build();

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(LocalRuntimeCapabilities.RPC_TRANSPORT)
            .provide(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY)
            .require(LocalRuntimeCapabilities.LOG_APPENDER)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.EXTERNAL)
            .health(HealthPhase.STARTUP)
            .build();
    private final ProductionAdapterDiagnostic diagnostic;
    private final ProductionStartupBudget startupBudget;
    private final Map<String, Object> clientProperties;
    private final List<ConfigSource> configSources;

    private ProductionKafkaRpcProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final Map<String, Object> clientProperties,
            final List<ConfigSource> configSources) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.clientProperties = Map.copyOf(Objects.requireNonNull(clientProperties, "clientProperties"));
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final Map<String, Object> clientProperties) {
        Objects.requireNonNull(resolver, "resolver");
        ProductionStartupBudget checkedBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        Map<String, Object> checkedProperties = Map.copyOf(
                Objects.requireNonNull(clientProperties, "clientProperties"));
        if (!resolver.strictEnabled(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                ZeroProductionRuntimeConfigKeys.ADAPTER_RPC_KAFKA_ENABLED)) {
            return Resolution.disabled(disabledDiagnostic());
        }
        List<ResolvedProductionSetting> required = requiredSettings(resolver);
        ProductionAdapterDiagnostic diagnostic = diagnostic(required);
        if (required.stream().anyMatch(setting -> !setting.present())) {
            return Resolution.missing(diagnostic);
        }
        List<ResolvedProductionSetting> typedSettings = new ArrayList<>(required);
        typedSettings.addAll(validateOptionalSettings(resolver));
        ProductionKafkaRpcProvider provider = new ProductionKafkaRpcProvider(
                diagnostic,
                checkedBudget,
                checkedProperties,
                ProductionConfigSources.from(ID, typedSettings));
        return Resolution.enabled(diagnostic, provider);
    }

    static Map<String, Object> validateClientProperties(final Map<String, ?> properties) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : Objects.requireNonNull(properties, "properties").entrySet()) {
            String key = Objects.requireNonNull(entry.getKey(), "property key");
            Object value = Objects.requireNonNull(entry.getValue(), "property value");
            if (!allowedClientProperty(key)) {
                throw ProductionAdapterFailures.invalidConfig(
                        ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                        ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                        "builder.kafkaClientProperties");
            }
            copied.put(key, value);
        }
        return Map.copyOf(copied);
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        try {
            LogAppender logAppender = checked.require(LocalRuntimeCapabilities.LOG_APPENDER);
            KafkaRpcSettings settings = settings(checked.config());
            RpcTransportObserver observer = new KafkaProductionTelemetryObserver(logAppender);
            KafkaRpcLifecycleAdapter adapter = new KafkaRpcLifecycleAdapter(settings, observer);
            ProductionStartupHealthProbe healthProbe = new ProductionStartupHealthProbe(
                    diagnostic,
                    new KafkaClusterHealthCheck(settings),
                    startupBudget);
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            return ComponentContribution.builder()
                    .bind(LocalRuntimeCapabilities.RPC_TRANSPORT, adapter)
                    .bind(LocalRuntimeCapabilities.RPC_HANDLER_REGISTRY, adapter)
                    .lifecycle(new ProductionAdapterLifecycle(
                            diagnostic,
                            startupBudget,
                            adapter,
                            startupBudget.adapterBudget()))
                    .healthProbe(HealthPhase.STARTUP, healthProbe)
                    .build();
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = ProductionAdapterFailures.sanitize(
                    diagnostic.adapterName(),
                    ProductionAdapterFailurePhase.CLIENT_CREATION,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                    ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(),
                    failure);
            diagnostic.fail(safeFailure);
            throw safeFailure;
        }
    }

    List<ConfigSource> configSources() {
        return configSources;
    }

    private KafkaRpcSettings settings(final ComponentConfig config) {
        int startupTimeoutMillis = boundedMillis(startupBudget.adapterBudget());
        Map<String, Object> producerProperties = new LinkedHashMap<>(clientProperties);
        producerProperties.put("request.timeout.ms", startupTimeoutMillis);
        producerProperties.put("max.block.ms", startupTimeoutMillis);
        Map<String, Object> consumerProperties = new LinkedHashMap<>(clientProperties);
        consumerProperties.put("request.timeout.ms", startupTimeoutMillis);
        consumerProperties.put("default.api.timeout.ms", startupTimeoutMillis);
        return new KafkaRpcSettings(
                config.require(BOOTSTRAP_SERVERS),
                config.require(CLIENT_ID),
                config.require(CONSUMER_GROUP_ID),
                config.require(TOPIC_PREFIX),
                config.require(REPLY_TOPIC),
                config.require(PENDING_CAPACITY),
                Duration.ofMillis(config.require(POLL_TIMEOUT_MILLIS)),
                Duration.ofMillis(config.require(CLOSE_TIMEOUT_MILLIS)),
                producerProperties,
                consumerProperties);
    }

    private static List<ResolvedProductionSetting> requiredSettings(
            final ProductionConfigResolver resolver) {
        return List.of(
                resolver.read(
                        ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                        ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                        true,
                        List.of(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS),
                        List.of(ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS),
                        List.of(ZeroProductionRuntimeConfigKeys.ENV_RPC_KAFKA_BOOTSTRAP_SERVERS)),
                directSetting(resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID),
                directSetting(resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID),
                directSetting(resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX),
                directSetting(resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC));
    }

    private static List<ResolvedProductionSetting> validateOptionalSettings(
            final ProductionConfigResolver resolver) {
        List<ResolvedProductionSetting> settings = new ArrayList<>();
        settings.add(optionalPositiveSetting(
                resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_PENDING_CAPACITY, 4096));
        settings.add(optionalPositiveSetting(
                resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_POLL_TIMEOUT_MILLIS, 100));
        settings.add(optionalPositiveSetting(
                resolver, ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLOSE_TIMEOUT_MILLIS, 3000));
        return List.copyOf(settings);
    }

    private static ResolvedProductionSetting optionalPositiveSetting(
            final ProductionConfigResolver resolver,
            final String key,
            final int defaultValue) {
        ResolvedProductionSetting setting = resolver.read(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                key,
                false,
                List.of(key),
                List.of(),
                List.of());
        resolver.strictPositiveInt(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC, key, defaultValue);
        return setting;
    }

    private static ResolvedProductionSetting directSetting(
            final ProductionConfigResolver resolver,
            final String key) {
        return resolver.read(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                key,
                true,
                List.of(key),
                List.of(key),
                List.of());
    }

    private static ProductionAdapterDiagnostic diagnostic(
            final List<ResolvedProductionSetting> settings) {
        List<String> requiredKeys = List.of(
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_BOOTSTRAP_SERVERS,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CLIENT_ID,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_CONSUMER_GROUP_ID,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_TOPIC_PREFIX,
                ZeroProductionRuntimeConfigKeys.RPC_KAFKA_REPLY_TOPIC);
        List<String> configured = settings.stream()
                .filter(ResolvedProductionSetting::present)
                .map(ResolvedProductionSetting::logicalKey)
                .sorted()
                .toList();
        List<String> missing = settings.stream()
                .filter(setting -> !setting.present())
                .map(ResolvedProductionSetting::logicalKey)
                .sorted()
                .toList();
        return new ProductionAdapterDiagnostic(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                missing.isEmpty() ? ZeroProductionAdapterState.ENABLED : ZeroProductionAdapterState.MISSING_CONFIG,
                requiredKeys,
                configured,
                missing,
                settings.stream().flatMap(setting -> setting.source().stream()).toList(),
                List.of(KafkaRpcLifecycleAdapter.class.getName(), KafkaRpcSettings.class.getName()));
    }

    private static ProductionAdapterDiagnostic disabledDiagnostic() {
        return new ProductionAdapterDiagnostic(
                ZeroProductionRuntimeBuilder.ADAPTER_KAFKA_RPC,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ConfigKey<String> sensitiveString(
            final String logicalName,
            final Set<ConfigSourceKind> sources,
            final String environmentAlias) {
        ConfigKey.Builder<String> builder = ConfigKey.string(ID, logicalName)
                .acceptedSources(sources)
                .validate(value -> !value.isBlank(), "non-blank")
                .sensitive();
        if (environmentAlias != null) {
            builder.alias(ConfigSourceKind.ENVIRONMENT, environmentAlias);
        }
        return builder.build();
    }

    private static ConfigKey<Integer> positiveInteger(final String logicalName, final int defaultValue) {
        return ConfigKey.integer(ID, logicalName)
                .acceptedSources(Set.of(ConfigSourceKind.PROGRAMMATIC))
                .defaultValue(defaultValue)
                .validate(value -> value > 0, "positive")
                .build();
    }

    static boolean allowedClientProperty(final String key) {
        if (ALLOWED_CLIENT_PROPERTY_KEYS.contains(key)) {
            return true;
        }
        return ALLOWED_CLIENT_PROPERTY_PREFIXES.stream()
                .anyMatch(prefix -> key.startsWith(prefix) && key.length() > prefix.length());
    }

    private int boundedMillis(final Duration timeout) {
        long millis = Math.max(1L, Objects.requireNonNull(timeout, "timeout").toMillis());
        return (int) Math.min(millis, Integer.MAX_VALUE);
    }

    /** Kafka provider 的显式选择和配置解析结果。 */
    record Resolution(
            boolean enabled,
            ProductionAdapterDiagnostic diagnostic,
            ProductionKafkaRpcProvider provider) {

        Resolution {
            Objects.requireNonNull(diagnostic, "diagnostic");
            if (enabled != (provider != null)) {
                throw new IllegalArgumentException("enabled Kafka resolution must contain exactly one provider");
            }
        }

        static Resolution enabled(
                final ProductionAdapterDiagnostic diagnostic,
                final ProductionKafkaRpcProvider provider) {
            return new Resolution(true, diagnostic, Objects.requireNonNull(provider, "provider"));
        }

        static Resolution missing(final ProductionAdapterDiagnostic diagnostic) {
            return new Resolution(false, diagnostic, null);
        }

        static Resolution disabled(final ProductionAdapterDiagnostic diagnostic) {
            return new Resolution(false, diagnostic, null);
        }
    }
}
