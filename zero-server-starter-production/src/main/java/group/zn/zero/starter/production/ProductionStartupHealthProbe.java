package group.zn.zero.starter.production;

import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthProbe;
import group.zn.zero.runtime.health.HealthRequest;
import group.zn.zero.runtime.health.HealthResult;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** 将现有同步 Production startup check 接入中立 runtime health 契约。 */
final class ProductionStartupHealthProbe implements HealthProbe {

    private final ProductionAdapterDiagnostic diagnostic;
    private final ProductionHealthProbe delegate;
    private final ProductionStartupBudget startupBudget;

    ProductionStartupHealthProbe(
            final ProductionAdapterDiagnostic diagnostic,
            final ProductionHealthProbe delegate,
            final ProductionStartupBudget startupBudget) {
        this.diagnostic = Objects.requireNonNull(diagnostic, "diagnostic");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.startupBudget = Objects.requireNonNull(startupBudget, "startupBudget");
    }

    @Override
    public CompletionStage<HealthResult> check(final HealthRequest request) {
        HealthRequest checked = Objects.requireNonNull(request, "request");
        if (checked.phase() != HealthPhase.STARTUP) {
            return CompletableFuture.failedFuture(safeFailure(null));
        }
        try {
            Duration timeout = minimum(
                    checked.timeout(),
                    startupBudget.remainingFor(diagnostic.adapterName()));
            delegate.check(timeout);
            diagnostic.mark(ZeroProductionAdapterState.HEALTHY);
            return CompletableFuture.completedFuture(
                    HealthResult.healthy("production-adapter-startup-healthy"));
        } catch (RuntimeException | Error failure) {
            ProductionAdapterException safeFailure = safeFailure(failure);
            diagnostic.fail(safeFailure);
            return CompletableFuture.failedFuture(safeFailure);
        }
    }

    String adapterName() {
        return diagnostic.adapterName();
    }

    ProductionHealthProbe delegate() {
        return delegate;
    }

    private ProductionAdapterException safeFailure(final Throwable failure) {
        return ProductionAdapterFailures.sanitize(
                diagnostic.adapterName(),
                ProductionAdapterFailurePhase.STARTUP_HEALTH,
                ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message(),
                failure);
    }

    private Duration minimum(final Duration left, final Duration right) {
        Duration checkedLeft = Objects.requireNonNull(left, "left");
        Duration checkedRight = Objects.requireNonNull(right, "right");
        return checkedLeft.compareTo(checkedRight) <= 0 ? checkedLeft : checkedRight;
    }
}
