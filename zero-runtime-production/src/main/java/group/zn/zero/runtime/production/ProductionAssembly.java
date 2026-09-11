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
            try {
                composition(resolved).diagnose();
            } catch (RuntimeException | Error failure) {
                throw ProductionAdapterFailures.sanitize("production-runtime",
                        ProductionAdapterFailurePhase.CONFIG_VALIDATION, ProductionAdapterErrorCode.CONFIG_INVALID,
                        ProductionAdapterErrorCode.CONFIG_INVALID.message(), failure);
            }
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
