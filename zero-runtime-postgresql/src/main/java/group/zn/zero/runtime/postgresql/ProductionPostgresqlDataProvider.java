package group.zn.zero.runtime.postgresql;

import group.zn.zero.data.postgresql.PostgresqlDataAdapter;
import group.zn.zero.data.repository.RepositorySource;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.capability.StandardRuntimeCapabilityModel;
import group.zn.zero.runtime.config.ConfigKey;
import group.zn.zero.runtime.config.ConfigSchema;
import group.zn.zero.runtime.config.ConfigSource;
import group.zn.zero.runtime.config.ConfigSourceKind;
import group.zn.zero.runtime.data.DataRuntime;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterLifecycle;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ProductionConfigSources;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import group.zn.zero.runtime.production.ProductionStartupHealthProbe;
import group.zn.zero.runtime.production.ResolvedProductionSetting;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionRuntimeConfigKeys;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentCreationContext;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.ComponentKind;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** PostgreSQL data Adapter 的显式 external runtime provider。 */
final class ProductionPostgresqlDataProvider implements RuntimeComponentProvider {

    /** 标准能力模型中的 provider ID。 */
    static final ComponentId ID = StandardRuntimeCapabilityModel.PRODUCTION_POSTGRESQL_DATA;

    /** PostgreSQL 配置允许使用的显式来源。 */
    private static final Set<ConfigSourceKind> SOURCES = Set.of(
            ConfigSourceKind.PROGRAMMATIC,
            ConfigSourceKind.SYSTEM_PROPERTY,
            ConfigSourceKind.ENVIRONMENT);

    /** JDBC URL typed config。 */
    private static final ConfigKey<String> JDBC_URL = sensitiveString(
            PostgresqlDriverSettings.JDBC_URL_PROPERTY,
            PostgresqlDriverSettings.JDBC_URL_ENV);

    /** 用户名 typed config。 */
    private static final ConfigKey<String> USERNAME = sensitiveString(
            PostgresqlDriverSettings.USERNAME_PROPERTY,
            PostgresqlDriverSettings.USERNAME_ENV);

    /** 密码 typed config。 */
    private static final ConfigKey<String> PASSWORD = sensitiveString(
            PostgresqlDriverSettings.PASSWORD_PROPERTY,
            PostgresqlDriverSettings.PASSWORD_ENV);

    /** 通用对象表名 typed config。 */
    private static final ConfigKey<String> TABLE_NAME = sensitiveString(
            PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
            PostgresqlDriverSettings.TABLE_NAME_ENV);

    /** PostgreSQL provider typed config schema。 */
    private static final ConfigSchema CONFIG_SCHEMA = ConfigSchema.builder(ID)
            .add(JDBC_URL)
            .add(USERNAME)
            .add(PASSWORD)
            .add(TABLE_NAME)
            .build();

    /** Runtime provider 描述。 */
    private final ComponentDescriptor descriptor = ComponentDescriptor.builder(ID)
            .provide(DataRuntime.DATA_SERVICES)
            .provide(DataRuntime.REPOSITORY_SOURCES)
            .configSchema(CONFIG_SCHEMA)
            .kind(ComponentKind.EXTERNAL)
            .health(HealthPhase.STARTUP)
            .build();

    /** 对外脱敏诊断状态。 */
    private final ProductionAdapterDiagnostic diagnostic;

    /** Production Adapter 共享累计启动预算。 */
    private final ProductionStartupBudget startupBudget;

    /** 已按 Production 优先级解析的 typed config 来源。 */
    private final List<ConfigSource> configSources;

    /** resolve 阶段预校验完成的 JDBC 配置。 */
    private final PostgresqlDriverSettings settings;

    /** Upper bound for runtime-owned physical database connections. */
    private final int maximumConnections;

    private ProductionPostgresqlDataProvider(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionStartupBudget startupBudget,
            final List<ConfigSource> configSources,
            final PostgresqlDriverSettings settings,
            final int maximumConnections) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        this.configSources = List.copyOf(Objects.requireNonNull(configSources, "configSources"));
        this.settings = Objects.requireNonNull(settings, "settings");
        this.maximumConnections = maximumConnections;
    }

    /**
     * 解析 PostgreSQL 显式选择、启动预算和敏感配置。
     *
     * <p>driver settings 在进入 runtime planner 前完成构造，以确保非法 SQL identifier 始终属于
     * config validation，而不会在 provider create 阶段误报 client creation。</p>
     *
     * @param resolver Production 配置解析器；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param maximumConnections Maximum number of runtime-owned physical connections.
     * @return PostgreSQL provider 解析结果；不可为空，线程安全。
     * @throws ProductionAdapterException 选择器、预算或配置非法时抛出安全异常。
     */
    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final int maximumConnections) {
        Objects.requireNonNull(resolver, "resolver");
        ProductionStartupBudget checkedBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        if (!resolver.strictEnabled(
                ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
                ZeroProductionRuntimeConfigKeys.ADAPTER_DATA_POSTGRESQL_ENABLED)) {
            return Resolution.disabled(disabledDiagnostic());
        }
        requireNativeTimeoutBudget(checkedBudget);
        List<ResolvedProductionSetting> resolvedSettings = settings(resolver);
        ProductionAdapterDiagnostic diagnostic = diagnostic(resolvedSettings);
        if (resolvedSettings.stream().anyMatch(setting -> !setting.present())) {
            return Resolution.missing(diagnostic);
        }
        PostgresqlDriverSettings driverSettings = driverSettings(resolvedSettings);
        return Resolution.enabled(
                diagnostic,
                new ProductionPostgresqlDataProvider(
                        diagnostic,
                        checkedBudget,
                        ProductionConfigSources.from(ID, resolvedSettings),
                        driverSettings,
                        maximumConnections));
    }

    @Override
    public ComponentDescriptor descriptor() {
        return descriptor;
    }

    @Override
    public ComponentContribution create(final ComponentCreationContext context) {
        Objects.requireNonNull(context, "context");
        try {
            PostgresqlDataAdapter createdAdapter = new PostgresqlDataAdapter();
            diagnostic.mark(ZeroProductionAdapterState.CREATED);
            var pool = context.resources().register(PostgresqlConnectionPool.create(
                    settings, maximumConnections, startupBudget.adapterBudget()));
            var repositories = context.resources().register(createdAdapter.repositoryFactory(pool, settings.tableName()));
            return ComponentContribution.builder()
                    .contribute(DataRuntime.DATA_SERVICES, createdAdapter)
                    .contribute(DataRuntime.REPOSITORY_SOURCES, new RepositorySource("postgresql", repositories))
                    .lifecycle(new ProductionAdapterLifecycle(
                            diagnostic,
                            startupBudget,
                            createdAdapter))
                    .healthProbe(HealthPhase.STARTUP, new ProductionStartupHealthProbe(
                            diagnostic,
                            timeout -> PostgresqlStartupProbe.check(settings, timeout),
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

    private static void requireNativeTimeoutBudget(final ProductionStartupBudget startupBudget) {
        if (startupBudget.adapterBudget().compareTo(Duration.ofSeconds(1)) < 0) {
            throw ProductionAdapterFailures.invalidConfig(
                    ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS);
        }
    }

    private static List<ResolvedProductionSetting> settings(final ProductionConfigResolver resolver) {
        return List.of(
                setting(
                        resolver,
                        PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                        PostgresqlDriverSettings.JDBC_URL_ENV),
                setting(
                        resolver,
                        PostgresqlDriverSettings.USERNAME_PROPERTY,
                        PostgresqlDriverSettings.USERNAME_ENV),
                setting(
                        resolver,
                        PostgresqlDriverSettings.PASSWORD_PROPERTY,
                        PostgresqlDriverSettings.PASSWORD_ENV),
                setting(
                        resolver,
                        PostgresqlDriverSettings.TABLE_NAME_PROPERTY,
                        PostgresqlDriverSettings.TABLE_NAME_ENV));
    }

    private static ResolvedProductionSetting setting(
            final ProductionConfigResolver resolver,
            final String logicalKey,
            final String environmentAlias) {
        return resolver.read(
                ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
                logicalKey,
                true,
                List.of(logicalKey),
                List.of(logicalKey),
                List.of(environmentAlias));
    }

    private static PostgresqlDriverSettings driverSettings(
            final List<ResolvedProductionSetting> settings) {
        return new PostgresqlDriverSettings(
                settings.get(0).require(),
                settings.get(1).require(),
                settings.get(2).require(),
                settings.get(3).require());
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
                ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
                missing.isEmpty() ? ZeroProductionAdapterState.ENABLED : ZeroProductionAdapterState.MISSING_CONFIG,
                List.of(
                        PostgresqlDriverSettings.JDBC_URL_PROPERTY,
                        PostgresqlDriverSettings.USERNAME_PROPERTY,
                        PostgresqlDriverSettings.PASSWORD_PROPERTY,
                        PostgresqlDriverSettings.TABLE_NAME_PROPERTY),
                configured,
                missing,
                settings.stream().flatMap(setting -> setting.source().stream()).toList(),
                List.of(PostgresqlDataAdapter.class.getName(), PostgresqlDriverSettings.class.getName()));
    }

    private static ProductionAdapterDiagnostic disabledDiagnostic() {
        return new ProductionAdapterDiagnostic(
                ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
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

    /** PostgreSQL provider 的显式选择和配置解析结果。 */
    record Resolution(
            boolean enabled,
            ProductionAdapterDiagnostic diagnostic,
            ProductionPostgresqlDataProvider provider) {

        Resolution {
            Objects.requireNonNull(diagnostic, "diagnostic");
            if (enabled != (provider != null)) {
                throw new IllegalArgumentException(
                        "enabled PostgreSQL resolution must contain exactly one provider");
            }
        }

        static Resolution enabled(
                final ProductionAdapterDiagnostic diagnostic,
                final ProductionPostgresqlDataProvider provider) {
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
