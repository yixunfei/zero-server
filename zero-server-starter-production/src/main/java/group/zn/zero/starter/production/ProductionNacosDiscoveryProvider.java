package group.zn.zero.starter.production;

import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoveryFactory;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.discovery.nacos.NacosHealthUpdateMode;
import group.zn.zero.discovery.nacos.ServiceDiscovery;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
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
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

/** Nacos ServiceDiscovery 的显式 external runtime provider。 */
final class ProductionNacosDiscoveryProvider implements RuntimeComponentProvider {

    /** 标准能力模型中的 provider ID。 */
    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_NACOS_DISCOVERY;

    /** Nacos 配置允许使用的显式来源。 */
    private static final Set<ConfigSourceKind> SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY,
            ConfigSourceKind.ENVIRONMENT);

    /** Runtime provider 描述。 */
    private final ComponentDescriptor descriptor;

    /** 对外脱敏诊断状态。 */
    private final ProductionAdapterDiagnostic diagnostic;

    /** Production Adapter 共享累计启动预算。 */
    private final ProductionStartupBudget startupBudget;

    /** 已按 Production 优先级解析的 typed config 来源。 */
    private final List<ConfigSource> configSources;

    /** 已预校验且受单 Adapter 预算约束的 Nacos 配置。 */
    private final NacosDiscoverySettings settings;

    ProductionNacosDiscoveryProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final List<ConfigSource> configSources,
            final NacosDiscoverySettings settings) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
        this.settings = Objects.requireNonNull(settings, "settings");
        descriptor = ComponentDescriptor.builder(ID)
                .provide(ProductionRuntimeCapabilities.SERVICE_DISCOVERY)
                .configSchema(configSchema(this.settings))
                .kind(ComponentKind.EXTERNAL)
                .health(HealthPhase.STARTUP)
                .build();
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            ServiceDiscovery created = NacosDiscoveryFactory.nacos(settings);
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            return ComponentContribution.builder()
                    .bind(ProductionRuntimeCapabilities.SERVICE_DISCOVERY, created)
                    .lifecycle(new ProductionAdapterLifecycle(
                            diagnostic,
                            startupBudget,
                            created,
                            Duration.ofMillis(settings.requestTimeoutMillis())))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> verifyRunning(created),
                            startupBudget))
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

    /**
     * 返回已解析配置来源。
     *
     * @return 不可变、有序、可能为空的配置来源；不可为空，线程安全。
     */
    List<ConfigSource> configSources() {
        return configSources;
    }

    private void verifyRunning(final ServiceDiscovery current) {
        if (!current.running()) {
            throw ProductionAdapterFailures.failure(
                    ZeroProductionRuntimeBuilder.ADAPTER_NACOS_DISCOVERY,
                    ProductionAdapterFailurePhase.STARTUP_HEALTH,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message());
        }
    }

    private ConfigSchema configSchema(final NacosDiscoverySettings defaults) {
        return ConfigSchema.builder(ID)
                .add(requiredSensitive(
                        NacosDiscoveryConfigKeys.SERVER_ADDR,
                        NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR,
                        NacosDiscoveryConfigKeys.ENV_SERVER_ADDR))
                .add(requiredSensitive(
                        NacosDiscoveryConfigKeys.NAMESPACE,
                        NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE,
                        NacosDiscoveryConfigKeys.ENV_NAMESPACE))
                .add(optionalSensitive(
                        NacosDiscoveryConfigKeys.USERNAME,
                        NacosDiscoveryConfigKeys.SYSTEM_USERNAME,
                        NacosDiscoveryConfigKeys.ENV_USERNAME))
                .add(optionalSensitive(
                        NacosDiscoveryConfigKeys.PASSWORD,
                        NacosDiscoveryConfigKeys.SYSTEM_PASSWORD,
                        NacosDiscoveryConfigKeys.ENV_PASSWORD))
                .add(optionalSensitive(
                        NacosDiscoveryConfigKeys.ACCESS_KEY,
                        NacosDiscoveryConfigKeys.SYSTEM_ACCESS_KEY,
                        NacosDiscoveryConfigKeys.ENV_ACCESS_KEY))
                .add(optionalSensitive(
                        NacosDiscoveryConfigKeys.SECRET_KEY,
                        NacosDiscoveryConfigKeys.SYSTEM_SECRET_KEY,
                        NacosDiscoveryConfigKeys.ENV_SECRET_KEY))
                .add(requiredSensitive(
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP))
                .add(requiredSensitive(
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER,
                        NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_CLUSTER,
                        NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER))
                .add(positiveInteger(
                        NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS,
                        NacosDiscoveryConfigKeys.SYSTEM_REQUEST_TIMEOUT_MILLIS,
                        NacosDiscoveryConfigKeys.ENV_REQUEST_TIMEOUT_MILLIS,
                        defaults.requestTimeoutMillis()))
                .add(strictBoolean(
                        NacosDiscoveryConfigKeys.NAMING_LOAD_CACHE_AT_START,
                        NacosDiscoveryConfigKeys.SYSTEM_NAMING_LOAD_CACHE_AT_START,
                        NacosDiscoveryConfigKeys.ENV_NAMING_LOAD_CACHE_AT_START,
                        defaults.namingLoadCacheAtStart()))
                .add(healthMode(defaults.healthUpdateMode()))
                .build();
    }

    private ConfigKey<String> requiredSensitive(
            final String logicalName,
            final String systemAlias,
            final String environmentAlias) {
        return aliases(ConfigKey.string(ID, logicalName), systemAlias, environmentAlias)
                .validate(value -> !value.isBlank(), "non-blank")
                .sensitive()
                .build();
    }

    private ConfigKey<String> optionalSensitive(
            final String logicalName,
            final String systemAlias,
            final String environmentAlias) {
        return aliases(ConfigKey.string(ID, logicalName), systemAlias, environmentAlias)
                .defaultValue("")
                .sensitive()
                .build();
    }

    private ConfigKey<Integer> positiveInteger(
            final String logicalName,
            final String systemAlias,
            final String environmentAlias,
            final int defaultValue) {
        return aliases(ConfigKey.integer(ID, logicalName), systemAlias, environmentAlias)
                .defaultValue(defaultValue)
                .validate(value -> value > 0, "positive")
                .build();
    }

    private ConfigKey<Boolean> strictBoolean(
            final String logicalName,
            final String systemAlias,
            final String environmentAlias,
            final boolean defaultValue) {
        return aliases(
                        ConfigKey.builder(ID, logicalName, Boolean.class, this::parseStrictBoolean),
                        systemAlias,
                        environmentAlias)
                .defaultValue(defaultValue)
                .build();
    }

    private ConfigKey<NacosHealthUpdateMode> healthMode(final NacosHealthUpdateMode defaultValue) {
        return aliases(
                        ConfigKey.builder(
                                ID,
                                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE,
                                NacosHealthUpdateMode.class,
                                value -> NacosHealthUpdateMode.valueOf(value.toUpperCase(Locale.ROOT))),
                        NacosDiscoveryConfigKeys.SYSTEM_HEALTH_UPDATE_MODE,
                        NacosDiscoveryConfigKeys.ENV_HEALTH_UPDATE_MODE)
                .defaultValue(defaultValue)
                .build();
    }

    private <T> ConfigKey.Builder<T> aliases(
            final ConfigKey.Builder<T> builder,
            final String systemAlias,
            final String environmentAlias) {
        return builder.acceptedSources(SOURCES)
                .alias(ConfigSourceKind.SYSTEM_PROPERTY, systemAlias)
                .alias(ConfigSourceKind.ENVIRONMENT, environmentAlias);
    }

    private Boolean parseStrictBoolean(final String value) {
        return switch (value) {
            case "true" -> true;
            case "false" -> false;
            default -> throw new IllegalArgumentException("expected true or false");
        };
    }
}
