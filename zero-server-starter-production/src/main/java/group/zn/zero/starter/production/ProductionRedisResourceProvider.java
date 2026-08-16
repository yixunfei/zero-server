package group.zn.zero.starter.production;

import group.zn.zero.data.redis.RedisDriverSettings;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ComponentConfig;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Redis data/cache 显式共享的无驱动公开面的资源句柄 provider。 */
final class ProductionRedisResourceProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_REDIS_RESOURCE;

    private static final ConfigKey<String> URI = ConfigKey.string(ID, RedisDriverSettings.PROPERTY_REDIS_URI)
            .acceptedSources(Set.of(
                    ConfigSourceKind.PROGRAMMATIC,
                    ConfigSourceKind.SYSTEM_PROPERTY,
                    ConfigSourceKind.ENVIRONMENT))
            .alias(ConfigSourceKind.ENVIRONMENT, RedisDriverSettings.ENV_REDIS_URI)
            .validate(value -> !value.isBlank(), "non-blank")
            .sensitive()
            .build();
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID).add(URI).build();

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(ProductionRedisRuntimeCapabilities.RESOURCE)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.FOUNDATION)
            .build();
    private final ProductionStartupBudget startupBudget;
    private final String failureOwner;
    private final List<ProductionAdapterDiagnostic> diagnostics;
    private final List<ConfigSource> configSources;

    ProductionRedisResourceProvider(
            final ProductionStartupBudget startupBudget,
            final String failureOwner,
            final List<ProductionAdapterDiagnostic> diagnostics,
            final List<ConfigSource> configSources) {
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.failureOwner = Objects.requireNonNull(failureOwner, "failureOwner");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentConfig config = Objects.requireNonNull(context, "context").config();
        ProductionRedisResource resource = new ProductionRedisResource(
                new RedisDriverSettings(config.require(URI)),
                startupBudget,
                failureOwner,
                diagnostics);
        return ComponentContribution.builder()
                .bind(ProductionRedisRuntimeCapabilities.RESOURCE, resource)
                .build();
    }

    List<ConfigSource> configSources() {
        return configSources;
    }

}
