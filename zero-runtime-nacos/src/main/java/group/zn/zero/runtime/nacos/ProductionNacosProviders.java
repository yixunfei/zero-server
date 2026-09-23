package group.zn.zero.runtime.nacos;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.discovery.nacos.NacosDiscoveryConfigKeys;
import group.zn.zero.discovery.nacos.NacosDiscoverySettings;
import group.zn.zero.discovery.ServiceDiscovery;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionConfigResolver;
import group.zn.zero.runtime.production.ProductionConfigSources;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import group.zn.zero.runtime.production.ResolvedProductionSetting;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Function;
import java.util.List;
import java.util.Objects;

/** Nacos discovery 与 RPC resolver provider 的原子选择和配置解析。 */
final class ProductionNacosProviders {

    private ProductionNacosProviders() {
    }

    /**
     * 解析 Nacos mode、必填配置、可选配置和单 Adapter timeout。
     *
     * @param resolver Production 配置解析器；不可为空。
     * @param startupBudget 共享累计启动预算；不可为空。
     * @param config 统一配置；不可为空。
     * @param systemPropertyLookup JVM system property 读取函数；不可为空，可返回空。
     * @param environmentLookup 环境变量读取函数；不可为空，可返回空。
     * @return Nacos provider family 解析结果；不可为空，线程安全。
     */
    static Resolution resolve(
            final ProductionConfigResolver resolver,
            final ProductionStartupBudget startupBudget,
            final ZeroConfig config,
            final Function<String, String> systemPropertyLookup,
            final Function<String, String> environmentLookup) {
        ProductionConfigResolver checkedResolver = Objects.requireNonNull(resolver, "resolver");
        ProductionStartupBudget checkedBudget = Objects.requireNonNull(startupBudget, "startupBudget");
        String mode = checkedResolver.strictChoice(
                ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                NacosDiscoveryConfigKeys.DISCOVERY_MODE,
                NacosDiscoveryConfigKeys.MODE_LOCAL,
                List.of(NacosDiscoveryConfigKeys.MODE_LOCAL, NacosDiscoveryConfigKeys.MODE_NACOS));
        if (NacosDiscoveryConfigKeys.MODE_LOCAL.equals(mode)) {
            return Resolution.disabled(disabledDiagnostic());
        }

        List<ResolvedProductionSetting> required = requiredSettings(checkedResolver);
        ProductionAdapterDiagnostic diagnostic = diagnostic(required);
        if (required.stream().anyMatch(setting -> !setting.present())) {
            return Resolution.missing(diagnostic);
        }
        NacosDiscoverySettings baseSettings = NacosDiscoverySettings.fromConfig(
                Objects.requireNonNull(config, "config"),
                Objects.requireNonNull(systemPropertyLookup, "systemPropertyLookup"),
                Objects.requireNonNull(environmentLookup, "environmentLookup"));
        ResolvedProductionSetting requestTimeout = strictSetting(
                checkedResolver,
                NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS,
                false,
                NacosDiscoveryConfigKeys.SYSTEM_REQUEST_TIMEOUT_MILLIS,
                NacosDiscoveryConfigKeys.ENV_REQUEST_TIMEOUT_MILLIS);
        NacosDiscoverySettings settings = boundedSettings(
                baseSettings,
                requestTimeout,
                checkedBudget);
        List<ResolvedProductionSetting> typedSettings = new ArrayList<>(required);
        typedSettings.addAll(credentialSettings(checkedResolver));
        typedSettings.add(requestTimeout);
        typedSettings.add(strictSetting(
                checkedResolver,
                NacosDiscoveryConfigKeys.NAMING_LOAD_CACHE_AT_START,
                false,
                NacosDiscoveryConfigKeys.SYSTEM_NAMING_LOAD_CACHE_AT_START,
                NacosDiscoveryConfigKeys.ENV_NAMING_LOAD_CACHE_AT_START));
        typedSettings.add(defaultableSetting(
                checkedResolver,
                NacosDiscoveryConfigKeys.HEALTH_UPDATE_MODE,
                false,
                NacosDiscoveryConfigKeys.SYSTEM_HEALTH_UPDATE_MODE,
                NacosDiscoveryConfigKeys.ENV_HEALTH_UPDATE_MODE));

        ProductionNacosDiscoveryProvider discoveryProvider = new ProductionNacosDiscoveryProvider(
                diagnostic,
                checkedBudget,
                ProductionConfigSources.from(ProductionNacosDiscoveryProvider.ID, typedSettings),
                settings);
        return Resolution.enabled(
                diagnostic,
                discoveryProvider,
                new ProductionNacosRpcResolverProvider(diagnostic));
    }

    private static List<ResolvedProductionSetting> requiredSettings(
            final ProductionConfigResolver resolver) {
        return List.of(
                strictSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.SERVER_ADDR,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_SERVER_ADDR,
                        NacosDiscoveryConfigKeys.ENV_SERVER_ADDR),
                strictSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.NAMESPACE,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_NAMESPACE,
                        NacosDiscoveryConfigKeys.ENV_NAMESPACE),
                strictSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.ENV_DEFAULT_GROUP),
                strictSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_DEFAULT_CLUSTER,
                        NacosDiscoveryConfigKeys.ENV_DEFAULT_CLUSTER));
    }

    private static List<ResolvedProductionSetting> credentialSettings(
            final ProductionConfigResolver resolver) {
        return List.of(
                defaultableSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.USERNAME,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_USERNAME,
                        NacosDiscoveryConfigKeys.ENV_USERNAME),
                defaultableSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.PASSWORD,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_PASSWORD,
                        NacosDiscoveryConfigKeys.ENV_PASSWORD),
                defaultableSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.ACCESS_KEY,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_ACCESS_KEY,
                        NacosDiscoveryConfigKeys.ENV_ACCESS_KEY),
                defaultableSetting(
                        resolver,
                        NacosDiscoveryConfigKeys.SECRET_KEY,
                        true,
                        NacosDiscoveryConfigKeys.SYSTEM_SECRET_KEY,
                        NacosDiscoveryConfigKeys.ENV_SECRET_KEY));
    }

    private static ResolvedProductionSetting strictSetting(
            final ProductionConfigResolver resolver,
            final String logicalKey,
            final boolean sensitive,
            final String systemAlias,
            final String environmentAlias) {
        return resolver.read(
                ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                logicalKey,
                sensitive,
                List.of(logicalKey),
                List.of(systemAlias),
                List.of(environmentAlias));
    }

    private static ResolvedProductionSetting defaultableSetting(
            final ProductionConfigResolver resolver,
            final String logicalKey,
            final boolean sensitive,
            final String systemAlias,
            final String environmentAlias) {
        return resolver.readDefaultable(
                ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                logicalKey,
                sensitive,
                List.of(logicalKey),
                List.of(systemAlias),
                List.of(environmentAlias));
    }

    private static NacosDiscoverySettings boundedSettings(
            final NacosDiscoverySettings base,
            final ResolvedProductionSetting configuredTimeout,
            final ProductionStartupBudget startupBudget) {
        int maximum = boundedMillis(startupBudget.adapterBudget());
        if (configuredTimeout.present() && base.requestTimeoutMillis() > maximum) {
            throw ProductionAdapterFailures.invalidConfig(
                    ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    NacosDiscoveryConfigKeys.REQUEST_TIMEOUT_MILLIS);
        }
        return new NacosDiscoverySettings(
                base.serverAddr(),
                base.namespace(),
                base.username(),
                base.password(),
                base.accessKey(),
                base.secretKey(),
                base.defaultGroupName(),
                base.defaultClusterName(),
                Math.min(base.requestTimeoutMillis(), maximum),
                base.namingLoadCacheAtStart(),
                base.healthUpdateMode(),
                base.extraProperties());
    }

    private static int boundedMillis(final Duration timeout) {
        long millis = Math.max(1L, Objects.requireNonNull(timeout, "timeout").toMillis());
        return (int) Math.min(millis, Integer.MAX_VALUE);
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
                ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                missing.isEmpty() ? ZeroProductionAdapterState.ENABLED : ZeroProductionAdapterState.MISSING_CONFIG,
                List.of(
                        NacosDiscoveryConfigKeys.SERVER_ADDR,
                        NacosDiscoveryConfigKeys.NAMESPACE,
                        NacosDiscoveryConfigKeys.DEFAULT_GROUP,
                        NacosDiscoveryConfigKeys.DEFAULT_CLUSTER),
                configured,
                missing,
                settings.stream().flatMap(setting -> setting.source().stream()).toList(),
                List.of(ServiceDiscovery.class.getName()));
    }

    private static ProductionAdapterDiagnostic disabledDiagnostic() {
        return new ProductionAdapterDiagnostic(
                ProductionAdapterNames.ADAPTER_NACOS_DISCOVERY,
                ZeroProductionAdapterState.DISABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
    }

    /** Nacos provider family 的完整选择结果。 */
    record Resolution(
            ProductionAdapterDiagnostic diagnostic,
            ProductionNacosDiscoveryProvider discoveryProvider,
            ProductionNacosRpcResolverProvider resolverProvider) {

        Resolution {
            Objects.requireNonNull(diagnostic, "diagnostic");
            if ((discoveryProvider == null) != (resolverProvider == null)) {
                throw new IllegalArgumentException(
                        "Nacos discovery and RPC resolver providers must be selected together");
            }
        }

        static Resolution enabled(
                final ProductionAdapterDiagnostic diagnostic,
                final ProductionNacosDiscoveryProvider discoveryProvider,
                final ProductionNacosRpcResolverProvider resolverProvider) {
            return new Resolution(
                    diagnostic,
                    Objects.requireNonNull(discoveryProvider, "discoveryProvider"),
                    Objects.requireNonNull(resolverProvider, "resolverProvider"));
        }

        static Resolution missing(final ProductionAdapterDiagnostic diagnostic) {
            return new Resolution(diagnostic, null, null);
        }

        static Resolution disabled(final ProductionAdapterDiagnostic diagnostic) {
            return new Resolution(diagnostic, null, null);
        }

        boolean enabled() {
            return discoveryProvider != null;
        }
    }
}
