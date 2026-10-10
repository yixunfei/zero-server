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
import group.zn.zero.runtime.health.HealthRequest;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.health.RuntimeHealthSnapshot;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.RuntimeDeadline;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletionStage;
import java.util.function.LongSupplier;
import java.util.concurrent.locks.ReentrantLock;

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
    /** lifecycle stop 和所有资源关闭的累计预算。 */
    private final Duration shutdownTimeout;
    /** 隔离阻塞生命周期调用，最多一个真实在途操作。 */
    private final LifecycleCallBoundary calls = new LifecycleCallBoundary();
    private final LongSupplier nanoTime;
    private final HealthProbeRunner healthProbeRunner = new HealthProbeRunner();
    /** 串行化生命周期请求，但不跨越状态锁执行组件代码。 */
    private final ReentrantLock lifecycleOperation = new ReentrantLock();
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
            final Duration shutdownTimeout,
            final LongSupplier nanoTime) {
        this.plan = Objects.requireNonNull(plan, "plan");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.components = List.copyOf(Objects.requireNonNull(components, "components"));
        this.resources = Objects.requireNonNull(resources, "resources");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.startupTimeout = Objects.requireNonNull(startupTimeout, "startupTimeout");
        this.shutdownTimeout = Objects.requireNonNull(shutdownTimeout, "shutdownTimeout");
        this.nanoTime = Objects.requireNonNull(nanoTime, "nanoTime");
    }

    @Override
    public void start() {
        lifecycleOperation.lock();
        try {
            RuntimeDeadline startupDeadline;
            synchronized (this) {
                if (startClaimed || terminalRequested || runtimeState != RuntimeState.BUILT) {
                    throw reuseFailure();
                }
                startClaimed = true;
                setState(RuntimeState.STARTING);
                startupDeadline = RuntimeDeadline.using(startupTimeout, nanoTime);
            }
            try {
                for (BuiltComponent component : components) {
                    startComponent(component, startupDeadline);
                }
                synchronized (this) {
                    setState(RuntimeState.RUNNING);
                }
            } catch (Throwable failure) {
                RuntimeAssemblyException primary = normalizeStartFailure(failure);
                synchronized (this) {
                    setState(RuntimeState.STOPPING);
                }
                RuntimeDeadline cleanupDeadline = RuntimeDeadline.using(shutdownTimeout, nanoTime);
                if (!calls.pending()) {
                    primary = startedComponents.stopAll(primary, tracker, calls, cleanupDeadline);
                    primary = resources.closeAll(primary, calls, cleanupDeadline);
                }
                synchronized (this) {
                    fail(primary);
                }
                throw primary;
            }
        } finally {
            lifecycleOperation.unlock();
        }
    }

    @Override
    public void stop() {
        if (rejectOrIgnoreReentrantShutdown()) {
            return;
        }
        RuntimeDeadline deadline = RuntimeDeadline.using(shutdownTimeout, nanoTime);
        acquireShutdown(deadline);
        try {
            synchronized (this) {
                if (runtimeState == RuntimeState.STARTING) throw reuseFailure();
                if (runtimeState == RuntimeState.STOPPING) return;
                if (runtimeState == RuntimeState.STOPPED || runtimeState == RuntimeState.CLOSED) {
                    return;
                }
                terminalRequested = true;
            }
            RuntimeAssemblyException failure = cleanup(deadline);
            if (failure != null) {
                synchronized (this) {
                    fail(failure);
                }
                throw failure;
            }
            synchronized (this) {
                setState(RuntimeState.STOPPED);
            }
        } finally {
            lifecycleOperation.unlock();
        }
    }

    @Override
    public void close() {
        if (rejectOrIgnoreReentrantShutdown()) {
            return;
        }
        RuntimeDeadline deadline = RuntimeDeadline.using(shutdownTimeout, nanoTime);
        acquireShutdown(deadline);
        try {
            synchronized (this) {
                if (runtimeState == RuntimeState.STARTING) throw reuseFailure();
                if (runtimeState == RuntimeState.STOPPING) return;
                if (runtimeState == RuntimeState.CLOSED) {
                    return;
                }
                if (runtimeState == RuntimeState.STOPPED) {
                    setState(RuntimeState.CLOSED);
                    return;
                }
                terminalRequested = true;
            }
            RuntimeAssemblyException failure = cleanup(deadline);
            if (failure != null) {
                synchronized (this) {
                    fail(failure);
                }
                throw failure;
            }
            synchronized (this) {
                setState(RuntimeState.CLOSED);
            }
        } finally {
            lifecycleOperation.unlock();
        }
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
            calls.call(() -> {
                lifecycle.start();
                startedComponents.record(componentId, lifecycle);
                return null;
            }, startupDeadline, componentId, RuntimeFailurePhase.START);
        } catch (Throwable failure) {
            tracker.startFailed(componentId, elapsed(startedAt));
            if (failure instanceof RuntimeAssemblyException known
                    && known.errorCode() == RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT) {
                throw known;
            }
            throw RuntimeAssemblyException.failure(
                    RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED,
                    RuntimeFailurePhase.START,
                    componentId,
                    "component=" + componentId);
        }
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
            java.time.Duration remaining = startupDeadline.remaining();
            if (remaining.isZero()) {
                throw calls.timeout(componentId, RuntimeFailurePhase.HEALTH);
            }
            CompletionStage<HealthResult> stage;
            try {
                HealthRequest request = new HealthRequest(HealthPhase.STARTUP, remaining, Instant.now());
                stage = calls.call(() -> Objects.requireNonNull(probe.check(request), "health stage"),
                        startupDeadline, componentId, RuntimeFailurePhase.HEALTH);
            } catch (RuntimeAssemblyException failure) { throw failure; }
            catch (Exception failure) {
                throw RuntimeAssemblyException.failure(RuntimeErrorCode.RUNTIME_COMPONENT_HEALTH_FAILED,
                        RuntimeFailurePhase.HEALTH, componentId, "component=" + componentId);
            }
            result = healthProbeRunner.await(componentId, stage, startupDeadline);
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

    private RuntimeAssemblyException cleanup(final RuntimeDeadline deadline) {
        synchronized (this) {
            setState(RuntimeState.STOPPING);
        }
        if (calls.pending()) return calls.timeout(null, RuntimeFailurePhase.STOP);
        RuntimeAssemblyException failure = startedComponents.stopAll(null, tracker, calls, deadline);
        return resources.closeAll(failure, calls, deadline);
    }

    /** 等待并发生命周期操作也计入预算，不能在获取操作锁之前无界阻塞。 */
    private void acquireShutdown(final RuntimeDeadline deadline) {
        try {
            if (!lifecycleOperation.tryLock(deadline.remaining().toNanos(), java.util.concurrent.TimeUnit.NANOSECONDS)) {
                throw calls.timeout(null, RuntimeFailurePhase.STOP);
            }
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw calls.timeout(null, RuntimeFailurePhase.STOP);
        }
    }

    /** 处理用户 lifecycle 回调内对当前 runtime 的关闭重入。 */
    private boolean rejectOrIgnoreReentrantShutdown() {
        if (!calls.inCall()) {
            return false;
        }
        RuntimeState state = runtimeState;
        if (state == RuntimeState.STOPPING) {
            return true;
        }
        throw reuseFailure();
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
                "runtime=start",
                safeCause(failure));
    }

    /** 已经是安全装配异常的失败不再把其可能包含的业务文本作为 cause 重新暴露。 */
    private Throwable safeCause(final Throwable failure) {
        return failure instanceof RuntimeAssemblyException ? null : failure;
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
