package group.zn.zero.runtime.assembly;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthProbe;
import group.zn.zero.runtime.health.HealthRequest;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * 在累计 deadline 内等待 mandatory startup probe。
 */
final class HealthProbeRunner {

    HealthResult await(
            final ComponentId componentId,
            final HealthProbe probe,
            final RuntimeDeadline deadline) {
        Duration remaining = deadline.remaining();
        if (remaining.isZero()) {
            throw timeout(componentId);
        }
        CompletionStage<HealthResult> stage;
        try {
            stage = Objects.requireNonNull(
                    probe.check(new HealthRequest(HealthPhase.STARTUP, remaining, Instant.now())),
                    "health stage");
        } catch (Throwable failure) {
            throw healthFailure(componentId);
        }
        CompletableFuture<HealthResult> future;
        try {
            future = Objects.requireNonNull(stage.toCompletableFuture(), "health future");
        } catch (Throwable failure) {
            throw healthFailure(componentId);
        }
        try {
            return Objects.requireNonNull(
                    future.get(timeoutNanos(remaining), TimeUnit.NANOSECONDS),
                    "health result");
        } catch (TimeoutException failure) {
            cancelBestEffort(future);
            throw timeout(componentId);
        } catch (InterruptedException failure) {
            cancelBestEffort(future);
            Thread.currentThread().interrupt();
            throw healthFailure(componentId);
        } catch (ExecutionException | RuntimeException failure) {
            throw healthFailure(componentId);
        } catch (Throwable failure) {
            throw healthFailure(componentId);
        }
    }

    private void cancelBestEffort(final CompletableFuture<?> future) {
        try {
            future.cancel(true);
        } catch (Throwable ignored) {
            // Preserve the timeout or interrupt as the stable primary failure.
        }
    }

    private RuntimeAssemblyException timeout(final ComponentId componentId) {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT,
                RuntimeFailurePhase.HEALTH,
                componentId,
                "component=" + componentId);
    }

    private RuntimeAssemblyException healthFailure(final ComponentId componentId) {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_COMPONENT_HEALTH_FAILED,
                RuntimeFailurePhase.HEALTH,
                componentId,
                "component=" + componentId);
    }

    private long timeoutNanos(final Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException overflow) {
            return Long.MAX_VALUE;
        }
    }
}
