package group.zn.zero.runtime.production;

import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.runtime.bootstrap.PeriodicAsyncTask;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.function.Supplier;

/** Coordinates bounded admission and periodic recovery for one real remote adapter. */
public final class RuntimeAdapterIo extends AbstractLifecycle {
    private final ProductionAdapterDiagnostic diagnostic;
    private final List<ProductionAdapterDiagnostic> diagnostics;
    private final ProductionHealthProbe probe;
    private final Predicate<Throwable> remoteFailure;
    private final RuntimeAdapterBudget budget;
    private final RuntimeAdapterResilience resilience;
    private final PeriodicAsyncTask periodic;
    private final AtomicInteger failures = new AtomicInteger();
    private final AtomicReference<RuntimeAdapterHealth.HealthSnapshot> health;
    private final Object stateLock = new Object();
    private long failureGeneration;
    private boolean closed;

    /** Creates a monitor whose probe runs only on the managed remote IO executor. */
    public RuntimeAdapterIo(final ProductionAdapterDiagnostic diagnostic,
            final ProductionHealthProbe probe, final Predicate<Throwable> remoteFailure,
            final ZeroRuntimeExecutors executors, final RuntimeAdapterBudget budget,
            final Duration probeInterval) {
        this(List.of(diagnostic), probe, remoteFailure, executors, budget, probeInterval);
    }

    /** Shares one health and admission state across multiple views of the same client. */
    public RuntimeAdapterIo(final List<ProductionAdapterDiagnostic> diagnostics,
            final ProductionHealthProbe probe, final Predicate<Throwable> remoteFailure,
            final ZeroRuntimeExecutors executors, final RuntimeAdapterBudget budget,
            final Duration probeInterval) {
        this.diagnostics = List.copyOf(Objects.requireNonNull(diagnostics, "diagnostics"));
        if (this.diagnostics.isEmpty()) throw new IllegalArgumentException("diagnostics must not be empty");
        this.diagnostic = this.diagnostics.get(0);
        requireRemoteIo(this.diagnostic.adapterName(), executors);
        this.probe = Objects.requireNonNull(probe, "probe");
        this.remoteFailure = Objects.requireNonNull(remoteFailure, "remoteFailure");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.health = new AtomicReference<>(snapshot(RuntimeAdapterHealth.Status.READY, "READY", Duration.ZERO));
        RuntimeAdapterHealth contract = new RuntimeAdapterHealth() {
            @Override public HealthSnapshot snapshot() { return health.get(); }
            @Override public CompletionStage<HealthSnapshot> probe(final Duration timeout) {
                return CompletableFuture.supplyAsync(() -> check(timeout), executors.remoteIoExecutor());
            }
        };
        resilience = RuntimeAdapterResilienceFactory.create(contract, RuntimeAdapterRecovery.conservative(), budget);
        periodic = new PeriodicAsyncTask(executors, diagnostic.adapterName() + "-health",
                Objects.requireNonNull(probeInterval, "probeInterval"), this::tick);
        this.diagnostics.forEach(value -> value.runtimeResilience(resilience));
    }

    /** 校验显式 IO 域；无资源副作用，失败只包含稳定配置提示。 */
    public static void requireRemoteIo(final String owner, final ZeroRuntimeExecutors executors) {
        if (Objects.requireNonNull(executors, "executors").remoteIoMayInline()) {
            throw ProductionAdapterFailures.failure(owner, ProductionAdapterFailurePhase.CONFIG_VALIDATION,
                    ProductionAdapterErrorCode.REMOTE_IO_EXECUTOR_REQUIRED,
                    ProductionAdapterErrorCode.REMOTE_IO_EXECUTOR_REQUIRED.message());
        }
    }

    /** 读取内存中的准入健康；线程安全，不触发远程探测，不泄露异常文本。 */
    public group.zn.zero.runtime.health.HealthResult readiness() {
        var current = snapshot();
        var status = current.state() == RuntimeAdapterStateMachine.State.READY
                && current.healthStatus() == RuntimeAdapterHealth.Status.READY
                ? group.zn.zero.runtime.health.HealthStatus.HEALTHY
                : group.zn.zero.runtime.health.HealthStatus.UNHEALTHY;
        return new group.zn.zero.runtime.health.HealthResult(status,
                current.state().name().toLowerCase(java.util.Locale.ROOT) + "."
                        + current.healthCode().toLowerCase(java.util.Locale.ROOT).replace('_', '-'));
    }

    /** 使用已解析的运行期设置；创建监测器不启动探测或线程。 */
    public static RuntimeAdapterIo create(final List<ProductionAdapterDiagnostic> diagnostics,
            final ProductionHealthProbe probe, final ZeroRuntimeExecutors executors,
            final RuntimeAdapterSettings settings) {
        return create(diagnostics, probe, RuntimeAdapterRemoteFailures::isRemoteIo, executors, settings);
    }

    /** 使用驱动故障分类及统一运行期设置；未知写结果仍不重试。 */
    public static RuntimeAdapterIo create(final List<ProductionAdapterDiagnostic> diagnostics,
            final ProductionHealthProbe probe, final Predicate<Throwable> remoteFailure,
            final ZeroRuntimeExecutors executors, final RuntimeAdapterSettings settings) {
        return new RuntimeAdapterIo(diagnostics, probe, remoteFailure, executors,
                settings.budget(), settings.probeInterval());
    }

    /** Executes one already scheduled synchronous IO operation; never retries a write. */
    public <T> T call(final boolean write, final Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        acquire();
        try {
            return operation.get();
        } catch (RuntimeException failure) {
            throw classify(failure, write);
        } finally {
            resilience.release();
        }
    }

    /** Observes asynchronous IO through completion without releasing its admission slot early. */
    public <T> CompletionStage<T> observe(final boolean write, final Supplier<CompletionStage<T>> operation) {
        Objects.requireNonNull(operation, "operation");
        acquire();
        AtomicBoolean released = new AtomicBoolean();
        try {
            CompletionStage<T> stage = Objects.requireNonNull(operation.get(), "operation stage");
            CompletableFuture<T> result = new CompletableFuture<>();
            stage.whenComplete((value, failure) -> {
                Throwable outcomeFailure = null;
                try {
                    if (failure != null) outcomeFailure = classify(unwrap(failure), write);
                } catch (Throwable classificationFailure) {
                    outcomeFailure = classificationFailure;
                } finally {
                    releaseOnce(released);
                }
                if (outcomeFailure == null) result.complete(value);
                else result.completeExceptionally(outcomeFailure);
            });
            return result;
        } catch (RuntimeException failure) {
            releaseOnce(released);
            throw classify(failure, write);
        } catch (Error failure) {
            releaseOnce(released);
            throw failure;
        }
    }

    /** Returns the live low-cardinality runtime snapshot. */
    public RuntimeAdapterResilienceSnapshot snapshot() { return resilience.diagnosticSnapshot(); }

    /** Schedules one connection probe on the managed remote IO executor. */
    public CompletionStage<RuntimeAdapterHealth.HealthSnapshot> probe() {
        return resilience.health().probe(budget.operationTimeout());
    }

    @Override protected void doStart() { periodic.start(); }

    @Override protected void doStop() {
        synchronized (stateLock) {
            closed = true;
            resilience.drain();
        }
        try { periodic.stop(); }
        finally {
            synchronized (stateLock) {
                resilience.close();
                diagnostics.forEach(value -> value.mark(ZeroProductionAdapterState.STOPPED));
                health.set(snapshot(RuntimeAdapterHealth.Status.UNAVAILABLE, "STOPPED", Duration.ZERO));
            }
        }
    }

    private CompletionStage<?> tick() {
        check(budget.operationTimeout());
        return CompletableFuture.completedFuture(null);
    }

    private RuntimeAdapterHealth.HealthSnapshot check(final Duration timeout) {
        long generation;
        synchronized (stateLock) {
            if (closed) return health.get();
            generation = failureGeneration;
            if (resilience.snapshot().state() == RuntimeAdapterStateMachine.State.DEGRADED) {
                resilience.recovering();
                diagnostics.forEach(value -> value.mark(ZeroProductionAdapterState.RECOVERING));
            }
        }
        long started = System.nanoTime();
        try {
            probe.check(timeout);
            synchronized (stateLock) {
                if (closed || generation != failureGeneration) return health.get();
                RuntimeAdapterHealth.HealthSnapshot result = snapshot(RuntimeAdapterHealth.Status.READY,
                        "READY", Duration.ofNanos(Math.max(0, System.nanoTime() - started)));
                health.set(result);
                resilience.recovered();
                failures.set(0);
                diagnostics.forEach(value -> {
                    if (value.snapshot().state() == ZeroProductionAdapterState.RECOVERING
                            || value.snapshot().state() == ZeroProductionAdapterState.DEGRADED) {
                        value.mark(ZeroProductionAdapterState.HEALTHY);
                    }
                });
                return result;
            }
        } catch (RuntimeException failure) {
            synchronized (stateLock) {
                if (generation == failureGeneration) degrade("PROBE_FAILED", true);
            }
            return health.get();
        }
    }

    private void acquire() {
        synchronized (stateLock) {
            if (closed || resilience.snapshot().state() != RuntimeAdapterStateMachine.State.READY
                    || !resilience.tryAcquire()) {
                throw new ProductionAdapterException(diagnostic.adapterName(),
                        ProductionAdapterFailurePhase.REMOTE_IO,
                        ProductionAdapterErrorCode.OPERATION_REJECTED,
                        ProductionAdapterErrorCode.OPERATION_REJECTED.message());
            }
        }
    }

    private RuntimeException classify(final Throwable failure, final boolean write) {
        if (!remoteFailure.test(failure)) {
            return failure instanceof RuntimeException runtime ? runtime : new CompletionException(failure);
        }
        degrade("REMOTE_IO_FAILED", !write);
        ProductionAdapterErrorCode code = write ? ProductionAdapterErrorCode.WRITE_OUTCOME_UNKNOWN
                : ProductionAdapterErrorCode.REMOTE_IO_FAILED;
        return new ProductionAdapterException(diagnostic.adapterName(),
                ProductionAdapterFailurePhase.REMOTE_IO, code, code.message());
    }

    private void degrade(final String code, final boolean outcomeKnown) {
        synchronized (stateLock) {
            if (closed) return;
            RuntimeAdapterStateMachine.State state = resilience.snapshot().state();
            if (state == RuntimeAdapterStateMachine.State.DRAIN
                    || state == RuntimeAdapterStateMachine.State.STOP
                    || state == RuntimeAdapterStateMachine.State.CLOSE) return;
            failureGeneration++;
            resilience.onFailure(new RuntimeAdapterRecovery.FailureContext(diagnostic.adapterName(),
                    "remote-io", true, outcomeKnown, failures.incrementAndGet(), code));
            health.set(snapshot(RuntimeAdapterHealth.Status.DEGRADED, code, Duration.ZERO));
            diagnostics.forEach(value -> value.mark(ZeroProductionAdapterState.DEGRADED));
        }
    }

    private void releaseOnce(final AtomicBoolean released) {
        if (released.compareAndSet(false, true)) resilience.release();
    }

    private RuntimeAdapterHealth.HealthSnapshot snapshot(final RuntimeAdapterHealth.Status status,
            final String code, final Duration latency) {
        return new RuntimeAdapterHealth.HealthSnapshot(diagnostic.adapterName(), status, code,
                Instant.now(), latency);
    }

    private static Throwable unwrap(final Throwable failure) {
        return failure instanceof CompletionException completion && completion.getCause() != null
                ? completion.getCause() : failure;
    }
}
