package group.zn.zero.starter.production;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot;
import group.zn.zero.starter.LocalRuntimeCapabilities;
import group.zn.zero.starter.ZeroRuntimeConfigKeys;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** 直接实现中立 {@link GameRuntime} 契约并保留脱敏 Production 诊断的运行时门面。 */
public final class ZeroProductionRuntime implements GameRuntime {

    private final String profile;
    private final GameRuntime delegate;
    private final List<ProductionAdapterDiagnostic> diagnostics;
    private boolean startClaimed;
    private boolean closeRequested;

    ZeroProductionRuntime(
            final String profile,
            final GameRuntime delegate,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        this.profile = Objects.requireNonNull(profile, "profile");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
    }

    @Override
    public <T> T require(final ComponentKey<T> key) {
        return delegate.require(key);
    }

    @Override
    public <T> Optional<T> optional(final ComponentKey<T> key) {
        return delegate.optional(key);
    }

    @Override
    public <T> List<T> requireAll(final ComponentSetKey<T> key) {
        return delegate.requireAll(key);
    }

    @Override
    public LifecycleState state() {
        return delegate.state();
    }

    @Override
    public RuntimeState runtimeState() {
        return delegate.runtimeState();
    }

    @Override
    public RuntimeAssemblyPlan plan() {
        return delegate.plan();
    }

    @Override
    public RuntimeAssemblyReport report() {
        return delegate.report();
    }

    @Override
    public RuntimeHealthSnapshot healthSnapshot() {
        return delegate.healthSnapshot();
    }

    /**
     * 返回包含 Adapter 状态的脱敏 Production 报告。
     *
     * @return Production 报告；不可为空，线程安全。
     */
    public ZeroProductionAssemblyReport productionReport() {
        Map<String, ZeroProductionAdapterStatus> statuses = new LinkedHashMap<>();
        for (ProductionAdapterDiagnostic diagnostic : diagnostics) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            statuses.put(status.adapterName(), status);
        }
        return new ZeroProductionAssemblyReport(
                profile,
                require(LocalRuntimeCapabilities.CONFIG).getOrDefault(
                        ZeroRuntimeConfigKeys.ZERO_NAME,
                        ZeroRuntimeConfigKeys.DEFAULT_NAME),
                report(),
                statuses,
                lifecycleComponentTypes(),
                List.of());
    }

    @Override
    public synchronized void start() {
        if (startClaimed || closeRequested) {
            throw reuseFailure();
        }
        startClaimed = true;
        try {
            delegate.start();
        } catch (RuntimeException | Error failure) {
            throw startFailure(failure);
        }
    }

    @Override
    public synchronized void stop() {
        closeRequested = true;
        try {
            delegate.stop();
        } catch (RuntimeException | Error failure) {
            throw closeFailure(failure);
        }
    }

    @Override
    public synchronized void close() {
        closeRequested = true;
        try {
            delegate.close();
        } catch (RuntimeException | Error failure) {
            throw closeFailure(failure);
        }
    }

    private ProductionAdapterException reuseFailure() {
        return ProductionAdapterFailures.failure(
                "production-runtime",
                ProductionAdapterFailurePhase.STARTUP,
                ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED,
                ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED.message());
    }

    private ProductionAdapterException startFailure(final Throwable failure) {
        for (ProductionAdapterDiagnostic diagnostic : diagnostics) {
            ZeroProductionAdapterStatus status = diagnostic.snapshot();
            if (status.state() == ZeroProductionAdapterState.FAILED
                    && startupFailurePhase(status.failurePhase())
                    && status.errorCode() != null) {
                return ProductionAdapterFailures.sanitize(
                        status.adapterName(),
                        status.failurePhase(),
                        status.errorCode(),
                        status.message(),
                        failure);
            }
        }
        return ProductionAdapterFailures.sanitize(
                "production-runtime",
                ProductionAdapterFailurePhase.STARTUP,
                ProductionAdapterErrorCode.STARTUP_FAILED,
                ProductionAdapterErrorCode.STARTUP_FAILED.message(),
                failure);
    }

    private ProductionAdapterException closeFailure(final Throwable failure) {
        return ProductionAdapterFailures.sanitize(
                "production-runtime",
                ProductionAdapterFailurePhase.CLOSE,
                ProductionAdapterErrorCode.CLOSE_FAILED,
                ProductionAdapterErrorCode.CLOSE_FAILED.message(),
                failure);
    }

    private boolean startupFailurePhase(final ProductionAdapterFailurePhase phase) {
        return switch (phase) {
            case CONNECT, AUTHENTICATION, STARTUP, REGISTRATION, STARTUP_HEALTH, STARTUP_BUDGET -> true;
            case NONE, CONFIG_SELECTION, CONFIG_VALIDATION, CLIENT_CREATION, ROLLBACK, CLOSE -> false;
        };
    }

    private List<String> lifecycleComponentTypes() {
        LinkedHashSet<String> types = new LinkedHashSet<>();
        Lifecycle persistence = require(LocalRuntimeCapabilities.PERSISTENCE_MANAGER);
        types.add(persistence.getClass().getName());
        requireAll(LocalRuntimeCapabilities.INFRASTRUCTURE_LIFECYCLES).stream()
                .map(value -> value.getClass().getName())
                .forEach(types::add);
        requireAll(LocalRuntimeCapabilities.APPLICATION_LIFECYCLES).stream()
                .map(value -> value.getClass().getName())
                .forEach(types::add);
        return List.copyOf(types);
    }
}
