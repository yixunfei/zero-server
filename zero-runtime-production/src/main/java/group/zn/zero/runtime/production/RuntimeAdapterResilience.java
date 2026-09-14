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
        inFlight.updateAndGet(value -> Math.max(0, value - 1));
    }

    public RuntimeAdapterRecovery.RecoveryDecision onFailure(
            final RuntimeAdapterRecovery.FailureContext failure) {
        RuntimeAdapterRecovery.RecoveryDecision decision = recovery.onFailure(
                Objects.requireNonNull(failure, "failure"));
        lastAttempt.accumulateAndGet(failure.attempt(), Math::max);
        lastDecision = decision;
        if (decision == RuntimeAdapterRecovery.RecoveryDecision.RETRY
                && failure.attempt() > budget.maxRetry()) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.FAILED);
            lastDecision = RuntimeAdapterRecovery.RecoveryDecision.FAIL;
            return lastDecision;
        }
        if (decision == RuntimeAdapterRecovery.RecoveryDecision.RETRY
                || decision == RuntimeAdapterRecovery.RecoveryDecision.REBUILD) {
            if (stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.READY) {
                stateMachine.transition(RuntimeAdapterStateMachine.State.DEGRADED);
            }
            stateMachine.transition(RuntimeAdapterStateMachine.State.RECOVERING);
        } else if (decision == RuntimeAdapterRecovery.RecoveryDecision.DRAIN) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.DRAIN);
        } else if (decision == RuntimeAdapterRecovery.RecoveryDecision.FAIL) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.FAILED);
        }
        return decision;
    }

    public void recovered() {
        if (stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.RECOVERING) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.READY);
        }
    }

    public void drain() {
        if (stateMachine.snapshot().state() != RuntimeAdapterStateMachine.State.DRAIN) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.DRAIN);
        }
    }

    public void stop() {
        if (stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.DRAIN) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.STOP);
        } else if (stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.READY
                || stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.FAILED) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.STOP);
        }
    }

    public void close() {
        if (stateMachine.snapshot().state() == RuntimeAdapterStateMachine.State.STOP) {
            stateMachine.transition(RuntimeAdapterStateMachine.State.CLOSE);
        }
    }
}
