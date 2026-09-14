package group.zn.zero.runtime.production;

import group.zn.zero.core.config.ZeroConfig;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeModule;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import java.time.Duration;
import java.util.ArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Lightweight production composition with explicit integration modules and no driver dependencies. */
public final class ProductionAssembly {
    private final ZeroConfig config;
    private final ZeroRuntimeExecutors executors;
    private final String profile;
    private final List<ProductionModuleFactory> factories = new ArrayList<>();
    private final List<Consumer<RuntimeComposition>> customizations = new ArrayList<>();
    private RuntimeModule base;
    private Function<String, String> systemProperties = System::getProperty;
    private Function<String, String> environment = System::getenv;
    private boolean consumed;

    private ProductionAssembly(final String profile, final ZeroConfig config, final ZeroRuntimeExecutors executors) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.config = ProductionProfiles.merge(profile, config);
        this.executors = Objects.requireNonNull(executors, "executors");
        base = RuntimeBasics.module(this.config, executors);
    }

    public static ProductionAssembly builder(final ZeroConfig config) {
        return builder(ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION, config, ZeroRuntimeExecutors.direct());
    }

    /**
     * 为单进程、外部测试或生产档位创建按需组合器；默认不创建线程。
     * @param profile standalone、external-test 或 production；必须与配置中的 zero.mode 一致。
     * @param config 配置输入；不可为空，不修改输入。
     * @return 非线程安全、尚未创建任何 Adapter 的组合器。
     * @throws group.zn.zero.core.error.ZeroException 档位无效或配置冲突时抛出。
     * @throws NullPointerException 档位或配置为空时抛出。
     */
    public static ProductionAssembly builder(final String profile, final ZeroConfig config) {
        return builder(profile, config, ZeroRuntimeExecutors.direct());
    }

    public static ProductionAssembly builder(
            final String profile, final ZeroConfig config, final ZeroRuntimeExecutors executors) {
        return new ProductionAssembly(profile, config, executors);
    }

    public ProductionAssembly base(final RuntimeModule module) {
        mutable();
        base = Objects.requireNonNull(module, "module");
        return this;
    }

    public ProductionAssembly install(final ProductionModuleFactory factory) {
        mutable();
        factories.add(Objects.requireNonNull(factory, "factory"));
        return this;
    }

    public ProductionAssembly install(final RuntimeModule module) {
        return configure(composition -> composition.install(module));
    }

    /** Applies explicit application providers, overrides and configuration before planning. */
    public ProductionAssembly configure(final Consumer<RuntimeComposition> customization) {
        mutable();
        customizations.add(Objects.requireNonNull(customization, "customization"));
        return this;
    }

    public ProductionAssembly configSourceLookups(
            final Function<String, String> properties, final Function<String, String> variables) {
        mutable();
        systemProperties = Objects.requireNonNull(properties, "properties");
        environment = Objects.requireNonNull(variables, "variables");
        return this;
    }

    public ZeroProductionAssemblyReport diagnose() {
        mutable();
        Resolved resolved = resolve();
        ZeroProductionAssemblyReport report = report(resolved);
        if (report.missingConfigKeys().isEmpty()) {
            plan(resolved);
        }
        return report;
    }

    public ZeroProductionRuntime build() {
        mutable();
        consumed = true;
        Resolved resolved = resolve();
        List<String> missing = report(resolved).missingConfigKeys();
        if (!missing.isEmpty()) {
            throw ProductionAdapterFailures.missingConfig(missing);
        }
        try {
            GameRuntime runtime = composition(resolved).build();
            return new ZeroProductionRuntime(profile, runtime, resolved.diagnostics());
        } catch (RuntimeException | Error failure) {
            throw creationFailure(resolved, failure);
        }
    }

    /**
     * 校验完整配置并返回实际选中的组件图；不创建客户端、执行器或连接外部服务。
     *
     * @return 不可变、拓扑有序、可能为空的组件计划；内容可跨线程读取。
     * @throws ProductionAdapterException 配置缺失、无效或组件图无法规划时抛出，异常已脱敏。
     * @throws IllegalStateException 组合器已被 build 消耗时抛出。
     * @implNote 此方法不消耗组合器，但组合器本身非线程安全；可在添加组件后再次规划。
     */
    public RuntimeAssemblyPlan plan() {
        mutable();
        Resolved resolved = resolve();
        List<String> missing = report(resolved).missingConfigKeys();
        if (!missing.isEmpty()) {
            throw ProductionAdapterFailures.missingConfig(missing);
        }
        return plan(resolved);
    }

    private RuntimeAssemblyPlan plan(final Resolved resolved) {
        try {
            return composition(resolved).diagnose();
        } catch (RuntimeException | Error failure) {
            throw ProductionAdapterFailures.sanitize("production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION, ProductionAdapterErrorCode.CONFIG_INVALID,
                    ProductionAdapterErrorCode.CONFIG_INVALID.message(), failure);
        }
    }

    private Resolved resolve() {
        try {
            ProductionConfigResolver resolver = new ProductionConfigResolver(config, systemProperties, environment);
            ProductionStartupBudget budget = budget(resolver);
            ProductionContext context = new ProductionContext(
                    config, resolver, budget, executors, systemProperties, environment);
            List<ProductionModule> modules = factories.stream().map(factory -> factory.resolve(context)).toList();
            validateSingleSelections(modules);
            List<ProductionAdapterDiagnostic> diagnostics = modules.stream()
                    .flatMap(module -> module.diagnostics().stream()).toList();
            return new Resolved(modules, diagnostics, budget);
        } catch (ProductionAdapterException failure) {
            throw failure;
        } catch (RuntimeException | Error failure) {
            throw ProductionAdapterFailures.sanitize("production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION, ProductionAdapterErrorCode.CONFIG_INVALID,
                    ProductionAdapterErrorCode.CONFIG_INVALID.message(), failure);
        }
    }

    private void validateSingleSelections(final List<ProductionModule> modules) {
        var selected = new LinkedHashMap<ComponentKey<?>, ComponentId>();
        for (ProductionModule module : modules) {
            for (var provider : module.providers()) {
                for (var key : provider.descriptor().provides()) {
                    if (key instanceof ComponentKey<?> single) {
                        ComponentId previous = selected.putIfAbsent(single, provider.descriptor().id());
                        if (previous != null && !previous.equals(provider.descriptor().id())) {
                            throw ProductionAdapterFailures.invalidConfig("production-runtime",
                                    ProductionAdapterFailurePhase.CONFIG_SELECTION, "module.single-capability");
                        }
                    }
                }
            }
        }
    }

    private RuntimeComposition composition(final Resolved resolved) {
        RuntimeProfile policy = switch (profile) {
            case ZeroProductionRuntimeConfigKeys.MODE_STANDALONE -> RuntimeProfile.standalone();
            case ZeroProductionRuntimeConfigKeys.MODE_PRODUCTION -> RuntimeProfile.production(Set.of());
            case ZeroProductionRuntimeConfigKeys.MODE_EXTERNAL_TEST -> RuntimeProfile.externalTest();
            default -> throw new IllegalArgumentException("unsupported production runtime profile");
        };
        RuntimeComposition composition = RuntimeComposition.builder(policy).install(base)
                .startupTimeout(resolved.budget().totalBudget());
        resolved.modules().forEach(module -> module.configure(composition, profile));
        customizations.forEach(customization -> customization.accept(composition));
        return composition;
    }

    private ProductionStartupBudget budget(final ProductionConfigResolver resolver) {
        int total = resolver.strictPositiveInt("production-runtime",
                ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_BUDGET_MILLIS,
                ZeroProductionRuntimeConfigKeys.DEFAULT_ADAPTER_STARTUP_BUDGET_MILLIS);
        int adapter = resolver.strictPositiveInt("production-runtime",
                ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS,
                ZeroProductionRuntimeConfigKeys.DEFAULT_ADAPTER_STARTUP_TIMEOUT_MILLIS);
        if (adapter > total) {
            throw ProductionAdapterFailures.invalidConfig("production-runtime",
                    ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_TIMEOUT_MILLIS);
        }
        return new ProductionStartupBudget(Duration.ofMillis(total), Duration.ofMillis(adapter));
    }

    private ZeroProductionAssemblyReport report(final Resolved resolved) {
        var statuses = new LinkedHashMap<String, ZeroProductionAdapterStatus>();
        for (ProductionAdapterDiagnostic diagnostic : resolved.diagnostics()) {
            if (statuses.putIfAbsent(diagnostic.adapterName(), diagnostic.snapshot()) != null) {
                throw ProductionAdapterFailures.invalidConfig(diagnostic.adapterName(),
                        ProductionAdapterFailurePhase.CONFIG_SELECTION, "module.duplicate");
            }
        }
        return new ZeroProductionAssemblyReport(
                profile, ZeroProductionAssemblyReport.REDACTED_RUNTIME_NAME, null, statuses, List.of(), List.of());
    }

    private ProductionAdapterException creationFailure(final Resolved resolved, final Throwable failure) {
        for (ProductionAdapterDiagnostic diagnostic : resolved.diagnostics()) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            if (status.state() == ZeroProductionAdapterState.FAILED && status.errorCode() != null) {
                return ProductionAdapterFailures.sanitize(status.adapterName(), status.failurePhase(),
                        status.errorCode(), status.message(), failure);
            }
        }
        return ProductionAdapterFailures.sanitize("production-runtime",
                ProductionAdapterFailurePhase.CLIENT_CREATION, ProductionAdapterErrorCode.CLIENT_CREATION_FAILED,
                ProductionAdapterErrorCode.CLIENT_CREATION_FAILED.message(), failure);
    }

    private void mutable() {
        if (consumed) {
            throw new IllegalStateException("production assembly has already been consumed");
        }
    }

    private record Resolved(
            List<ProductionModule> modules,
            List<ProductionAdapterDiagnostic> diagnostics,
            ProductionStartupBudget budget) {
    }
}
