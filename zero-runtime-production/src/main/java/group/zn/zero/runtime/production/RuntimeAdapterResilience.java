package group.zn.zero.runtime.production;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/** Deterministic resilience coordinator; callers own scheduling and executors. */
public final class RuntimeAdapterResilience {
    private final RuntimeAdapterHealth health;
    private final RuntimeAdapterRecovery recovery;
    private final RuntimeAdapterBudget budget;
    private final RuntimeAdapterStateMachine stateMachine;
    private final AtomicInteger inFlight = new AtomicInteger();
    private final AtomicInteger lastAttempt = new AtomicInteger();
    private volatile RuntimeAdapterRecovery.RecoveryDecision lastDecision =
            RuntimeAdapterRecovery.RecoveryDecision.IGNORE;

    public RuntimeAdapterResilience(
            final RuntimeAdapterHealth health,
            final RuntimeAdapterRecovery recovery,
            final RuntimeAdapterBudget budget) {
        this.health = Objects.requireNonNull(health, "health");
        this.recovery = Objects.requireNonNull(recovery, "recovery");
        this.budget = Objects.requireNonNull(budget, "budget");
        this.stateMachine = new RuntimeAdapterStateMachine(health.snapshot().adapterName());
    }

    public RuntimeAdapterStateMachine.Snapshot snapshot() {
        return stateMachine.snapshot();
    }

    public RuntimeAdapterResilienceSnapshot diagnosticSnapshot() {
        RuntimeAdapterHealth.HealthSnapshot healthSnapshot = health.snapshot();
        int attempt = lastAttempt.get();
        return new RuntimeAdapterResilienceSnapshot(
                healthSnapshot.adapterName(),
                stateMachine.snapshot().state(),
                healthSnapshot.status(),
                healthSnapshot.code(),
                inFlight.get(),
                budget.maxInFlight(),
                attempt,
                lastDecision,
                budget.operationTimeout(),
                budget.backoffFor(Math.max(1, attempt + 1)),
                healthSnapshot.observedAt());
    }

    public RuntimeAdapterHealth health() {
        return health;
    }

    public Duration operationTimeout() {
        return budget.operationTimeout();
    }

    public boolean tryAcquire() {
        while (true) {
            int current = inFlight.get();
            if (current >= budget.maxInFlight()) {
                return false;
            }
            if (inFlight.compareAndSet(current, current + 1)) {
                return true;
            }
        }
    }

    public void release() {
        inFlight.updateAndGet(value -> {
            if (value == 0) throw group.zn.zero.core.error.ZeroException.of(
                    group.zn.zero.core.error.SystemErrorCode.SYSTEM_ERROR, "adapter release without acquisition", null);
            return value - 1;
        });
    }

    public synchronized RuntimeAdapterRecovery.RecoveryDecision onFailure(
            final RuntimeAdapterRecovery.FailureContext failure) {
        RuntimeAdapterRecovery.RecoveryDecision decision = recovery.onFailure(
                Objects.requireNonNull(failure, "failure"));
        lastAttempt.accumulateAndGet(failure.attempt(), Math::max);
        lastDecision = decision;
        if (decision == RuntimeAdapterRecovery.RecoveryDecision.RETRY
                || decision == RuntimeAdapterRecovery.RecoveryDecision.REBUILD) {
            RuntimeAdapterStateMachine.Snapshot current = stateMachine.snapshot();
            if (current.state() == RuntimeAdapterStateMachine.State.READY) {
                stateMachine.transitionIf(
                        RuntimeAdapterStateMachine.State.READY,
                        RuntimeAdapterStateMachine.State.DEGRADED);
            }
            if (current.state() == RuntimeAdapterStateMachine.State.RECOVERING) {
                stateMachine.transitionIf(
                        RuntimeAdapterStateMachine.State.RECOVERING,
                        RuntimeAdapterStateMachine.State.DEGRADED);
            }
        } else if (decision == RuntimeAdapterRecovery.RecoveryDecision.DRAIN) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.DRAIN);
        } else if (decision == RuntimeAdapterRecovery.RecoveryDecision.FAIL) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.FAILED);
        }
        return decision;
    }

    public synchronized void recovered() {
        stateMachine.transitionIf(
                RuntimeAdapterStateMachine.State.RECOVERING,
                RuntimeAdapterStateMachine.State.READY);
        lastAttempt.set(0);
        lastDecision = RuntimeAdapterRecovery.RecoveryDecision.IGNORE;
    }

    /** Marks a scheduled probe as active without replaying a failed operation. */
    public synchronized void recovering() {
        stateMachine.transitionIf(
                RuntimeAdapterStateMachine.State.DEGRADED,
                RuntimeAdapterStateMachine.State.RECOVERING);
    }

    public synchronized void drain() {
        RuntimeAdapterStateMachine.Snapshot current = stateMachine.snapshot();
        if (current.state() != RuntimeAdapterStateMachine.State.DRAIN
                && current.state() != RuntimeAdapterStateMachine.State.STOP
                && current.state() != RuntimeAdapterStateMachine.State.CLOSE) {
            stateMachine.transitionIf(current.state(), RuntimeAdapterStateMachine.State.DRAIN);
        }
    }

    public synchronized void stop() {
        drain();
        RuntimeAdapterStateMachine.Snapshot current = stateMachine.snapshot();
        if (current.state() == RuntimeAdapterStateMachine.State.DRAIN
                || current.state() == RuntimeAdapterStateMachine.State.READY
                || current.state() == RuntimeAdapterStateMachine.State.FAILED) {
            stateMachine.transitionIf(current.state(), RuntimeAdapterStateMachine.State.STOP);
        }
    }

    public synchronized void close() {
        stop();
        stateMachine.transitionIf(RuntimeAdapterStateMachine.State.STOP, RuntimeAdapterStateMachine.State.CLOSE);
    }
}
