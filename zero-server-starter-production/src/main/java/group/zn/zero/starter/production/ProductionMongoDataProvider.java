package group.zn.zero.starter.production;

import com.mongodb.client.MongoClient;
import group.zn.zero.data.mongo.MongoDataAdapter;
import group.zn.zero.data.mongo.MongoDriverSettings;
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
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** MongoDB data Adapter 的显式 external runtime provider。 */
final class ProductionMongoDataProvider implements RuntimeComponentProvider {

    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_MONGO_DATA;

    private static final Set<ConfigSourceKind> SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY,
            ConfigSourceKind.ENVIRONMENT);
    private static final ConfigKey<String> URI = sensitiveString(
            MongoDriverSettings.PROPERTY_MONGO_URI,
            MongoDriverSettings.ENV_MONGO_URI);
    private static final ConfigKey<String> DATABASE = sensitiveString(
            MongoDriverSettings.PROPERTY_MONGO_DATABASE,
            MongoDriverSettings.ENV_MONGO_DATABASE);
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID)
            .add(URI)
            .add(DATABASE)
            .build();

    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(ProductionRuntimeCapabilities.DATA_SERVICES)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.EXTERNAL)
            .health(HealthPhase.STARTUP)
            .build();
    private final ProductionAdapterDiagnostic diagnostic;
    private final ProductionStartupBudget startupBudget;
    private final List<ConfigSource> configSources;

    private ProductionMongoDataProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final List<ConfigSource> configSources) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
    }

    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget) {
        Objects.requireNonNull(resolver, "resolver");
        ProductionStartupBudget checkedBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        if (!resolver.strictEnabled(
                ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_MONGO_ENABLED)) {
            return Resolution.disabled(disabledDiagnostic());
        }
        List<ResolvedProductionSetting> settings = List.of(
                setting(
                        resolver,
                        MongoDriverSettings.PROPERTY_MONGO_URI,
                        MongoDriverSettings.ENV_MONGO_URI),
                setting(
                        resolver,
                        MongoDriverSettings.PROPERTY_MONGO_DATABASE,
                        MongoDriverSettings.ENV_MONGO_DATABASE));
        ProductionAdapterDiagnostic diagnostic = diagnostic(settings);
        if (settings.stream().anyMatch(setting -> !setting.present())) {
            return Resolution.missing(diagnostic);
        }
        return Resolution.enabled(
                diagnostic,
                new ProductionMongoDataProvider(
                        diagnostic,
                        checkedBudget,
                        ProductionConfigSources.from(ID, settings)));
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        ComponentCreationContext checked = Objects.requireNonNull(context, "context");
        try {
            MongoDriverSettings settings = settings(checked.config());
            MongoDataAdapter createdAdapter = new MongoDataAdapter();
            MongoClient createdClient = checked.resources().register(createdAdapter.createClient(
                    settings,
                    startupBudget.adapterBudget()));
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            return ComponentContribution.builder()
                    .contribute(ProductionRuntimeCapabilities.DATA_SERVICES, createdAdapter)
                    .lifecycle(new ProductionAdapterLifecycle(
                            diagnostic,
                            startupBudget,
                            createdAdapter))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> ProductionAdapterHealthProbes.mongo(settings, timeout),
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

    List<ConfigSource> configSources() {
        return configSources;
    }

    private MongoDriverSettings settings(final ComponentConfig config) {
        return new MongoDriverSettings(config.require(URI), config.require(DATABASE));
    }

    private static ResolvedProductionSetting setting(
            final ProductionConfigResolver resolver,
            final String logicalKey,
            final String environmentAlias) {
        return resolver.read(
                ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                logicalKey,
                true,
                List.of(logicalKey),
                List.of(logicalKey),
                List.of(environmentAlias));
    }

    private static ProductionAdapterDiagnostic diagnostic(
            final List<ResolvedProductionSetting> settings) {
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
                ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                missing.isEmpty() ? ZeroProductionAdapterState.ENABLED : ZeroProductionAdapterState.MISSING_CONFIG,
                List.of(
                        MongoDriverSettings.PROPERTY_MONGO_URI,
                        MongoDriverSettings.PROPERTY_MONGO_DATABASE),
                configured,
                missing,
                settings.stream().flatMap(setting -> setting.source().stream()).toList(),
                List.of(MongoDataAdapter.class.getName(), MongoClient.class.getName()));
    }

    private static ProductionAdapterDiagnostic disabledDiagnostic() {
        return new ProductionAdapterDiagnostic(
                ZeroProductionRuntimeBuilder.ADAPTER_MONGO_DATA,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    private static ConfigKey<String> sensitiveString(
            final String logicalName,
            final String environmentAlias) {
        return ConfigKey.string(ID, logicalName)
                .acceptedSources(SOURCES)
                .alias(ConfigSourceKind.ENVIRONMENT, environmentAlias)
                .validate(value -> !value.isBlank(), "non-blank")
                .sensitive()
                .build();
    }

    /** Mongo provider 的显式选择和配置解析结果。 */
    record Resolution(
            boolean enabled,
            ProductionAdapterDiagnostic diagnostic,
            ProductionMongoDataProvider provider) {

        Resolution {
            Objects.requireNonNull(diagnostic, "diagnostic");
            if (enabled != (provider != null)) {
                throw new IllegalArgumentException("enabled Mongo resolution must contain exactly one provider");
            }
        }

        static Resolution enabled(
                final ProductionAdapterDiagnostic diagnostic,
                final ProductionMongoDataProvider provider) {
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
