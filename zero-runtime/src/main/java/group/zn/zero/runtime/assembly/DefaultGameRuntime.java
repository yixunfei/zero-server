package group.zn.zero.runtime.assembly;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.ComponentSetKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyPlan;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyReport;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.diagnostics.RuntimeFailurePhase;
import group.zn.zero.runtime.diagnostics.RuntimePhaseOutcome;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthProbe;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * 确定性拓扑、双 ledger 和 single-use 状态机的默认 runtime。
 */
final class DefaultGameRuntime implements GameRuntime {

    private final RuntimeAssemblyPlan plan;
    private final RuntimeBindings bindings;
    private final List<BuiltComponent> components;
    private final BuildResourceLedger resources;
    private final StartedComponentLedger startedComponents = new StartedComponentLedger();
    private final RuntimeReportTracker tracker;
    private final Duration startupTimeout;
    private final LongSupplier nanoTime;
    private final HealthProbeRunner healthProbeRunner = new HealthProbeRunner();
    private volatile RuntimeState runtimeState = RuntimeState.BUILT;
    private boolean startClaimed;
    private boolean terminalRequested;

    DefaultGameRuntime(
            final RuntimeAssemblyPlan plan,
            final RuntimeBindings bindings,
            final List<BuiltComponent> components,
            final BuildResourceLedger resources,
            final RuntimeReportTracker tracker,
            final Duration startupTimeout,
            final LongSupplier nanoTime) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        this.resources = Objects.requireNonNull(resources, "resources");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public synchronized void start() {
        if (startClaimed || terminalRequested || runtimeState != RuntimeState.BUILT) {
            throw reuseFailure();
        }
        startClaimed = true;
        setState(RuntimeState.STARTING);
        try {
            RuntimeDeadline startupDeadline = RuntimeDeadline.using(startupTimeout, nanoTime);
            for (BuiltComponent component : components) {
                startComponent(component, startupDeadline);
            }
            setState(RuntimeState.RUNNING);
        } catch (Throwable failure) {
            RuntimeAssemblyException primary = normalizeStartFailure(failure);
            primary = startedComponents.stopAll(primary, tracker);
            primary = resources.closeAll(primary);
            fail(primary);
            throw primary;
        }
    }

    @Override
    public synchronized void stop() {
        if (runtimeState == RuntimeState.STOPPED || runtimeState == RuntimeState.CLOSED) {
            return;
        }
        terminalRequested = true;
        RuntimeAssemblyException failure = cleanup();
        if (failure != null) {
            fail(failure);
            throw failure;
        }
        setState(RuntimeState.STOPPED);
    }

    @Override
    public synchronized void close() {
        if (runtimeState == RuntimeState.CLOSED) {
            return;
        }
        terminalRequested = true;
        RuntimeAssemblyException failure = cleanup();
        if (failure != null) {
            fail(failure);
            throw failure;
        }
        setState(RuntimeState.CLOSED);
    }

    @Override
    public LifecycleState state() {
        return switch (runtimeState) {
            case PLANNED, BUILT -> LifecycleState.NEW;
            case STARTING -> LifecycleState.STARTING;
            case RUNNING -> LifecycleState.RUNNING;
            case STOPPING -> LifecycleState.STOPPING;
            case STOPPED, CLOSED -> LifecycleState.STOPPED;
            case FAILED -> LifecycleState.FAILED;
        };
    }

    @Override
    public <T> T require(final ComponentKey<T> key) {
        return bindings.require(key);
    }

    @Override
    public <T> Optional<T> optional(final ComponentKey<T> key) {
        return bindings.optional(key);
    }

    @Override
    public <T> List<T> requireAll(final ComponentSetKey<T> key) {
        return bindings.requireAll(key);
    }

    @Override
    public RuntimeState runtimeState() {
        return runtimeState;
    }

    @Override
    public RuntimeAssemblyPlan plan() {
        return plan;
    }

    @Override
    public RuntimeAssemblyReport report() {
        return tracker.snapshot(resources);
    }

    @Override
    public RuntimeHealthSnapshot healthSnapshot() {
        return tracker.healthSnapshot();
    }

    private void startComponent(
            final BuiltComponent component,
            final RuntimeDeadline startupDeadline) {
        ensureBudget(component.componentId(), RuntimeFailurePhase.START, startupDeadline);
        ComponentContribution contribution = component.contribution();
        Optional<Lifecycle> lifecycle = contribution.lifecycle();
        lifecycle.ifPresent(value -> startLifecycle(component.componentId(), value, startupDeadline));
        contribution.healthProbes().entrySet().stream()
                .filter(entry -> entry.getKey() == HealthPhase.STARTUP)
                .findFirst()
                .ifPresent(entry -> runStartupHealth(
                        component.componentId(), entry.getValue(), startupDeadline));
    }

    private void startLifecycle(
            final ComponentId componentId,
            final Lifecycle lifecycle,
            final RuntimeDeadline startupDeadline) {
        long startedAt = System.nanoTime();
        try {
            lifecycle.start();
        } catch (Throwable failure) {
            tracker.startFailed(componentId, elapsed(startedAt));
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED,
                    RuntimeFailurePhase.START,
                    componentId,
                    "component=" + componentId);
        }
        startedComponents.record(componentId, lifecycle);
        tracker.started(componentId, elapsed(startedAt));
        ensureBudget(componentId, RuntimeFailurePhase.START, startupDeadline);
    }

    private void runStartupHealth(
            final ComponentId componentId,
            final HealthProbe probe,
            final RuntimeDeadline startupDeadline) {
        long startedAt = System.nanoTime();
        HealthResult result = null;
        try {
            result = healthProbeRunner.await(componentId, probe, startupDeadline);
            if (result.status() != HealthStatus.HEALTHY) {
                tracker.health(componentId, RuntimePhaseOutcome.FAILED, elapsed(startedAt), result);
                throw RuntimeAssemblyException.failure(
                        RuntimeErrorCode.RUNTIME_COMPONENT_HEALTH_FAILED,
                        RuntimeFailurePhase.HEALTH,
                        componentId,
                        "component=" + componentId + ";health-code=" + result.code());
            }
            tracker.health(componentId, RuntimePhaseOutcome.SUCCEEDED, elapsed(startedAt), result);
        } catch (RuntimeAssemblyException failure) {
            if (result == null) {
                tracker.health(componentId, RuntimePhaseOutcome.FAILED, elapsed(startedAt), null);
            }
            throw failure;
        }
    }

    private RuntimeAssemblyException cleanup() {
        setState(RuntimeState.STOPPING);
        RuntimeAssemblyException failure = startedComponents.stopAll(null, tracker);
        return resources.closeAll(failure);
    }

    private void ensureBudget(
            final ComponentId componentId,
            final RuntimeFailurePhase phase,
            final RuntimeDeadline startupDeadline) {
        if (startupDeadline.expired()) {
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT,
                    phase,
                    componentId,
                    "component=" + componentId);
        }
    }

    private RuntimeAssemblyException normalizeStartFailure(final Throwable failure) {
        if (failure instanceof RuntimeAssemblyException assemblyFailure) {
            return assemblyFailure;
        }
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED,
                RuntimeFailurePhase.START,
                null,
                "runtime=start");
    }

    private RuntimeAssemblyException reuseFailure() {
        return RuntimeAssemblyException.failure(
                RuntimeErrorCode.RUNTIME_REUSE_REJECTED,
                RuntimeFailurePhase.START,
                null,
                "runtime=single-use");
    }

    private void fail(final RuntimeAssemblyException failure) {
        runtimeState = RuntimeState.FAILED;
        tracker.failed(failure);
        failure.withReport(tracker.snapshot(resources));
    }

    private void setState(final RuntimeState state) {
        runtimeState = state;
        tracker.state(state);
    }

    private long elapsed(final long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }
}
