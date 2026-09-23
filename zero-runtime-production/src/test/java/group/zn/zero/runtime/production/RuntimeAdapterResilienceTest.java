package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class RuntimeAdapterResilienceTest {
    private static final RuntimeAdapterBudget BUDGET = new RuntimeAdapterBudget(
            Duration.ofSeconds(2), Duration.ofMillis(500), 1, 2,
            Duration.ofMillis(10), Duration.ofMillis(100), 2.0d);

    @Test
    void budgetBoundsBackoffAndConcurrency() {
        assertEquals(Duration.ofMillis(10), BUDGET.backoffFor(1));
        assertEquals(Duration.ofMillis(20), BUDGET.backoffFor(2));
        assertEquals(Duration.ofMillis(100), BUDGET.backoffFor(20));
        RuntimeAdapterHealth health = readyHealth();
        RuntimeAdapterResilience resilience = new RuntimeAdapterResilience(
                health, RuntimeAdapterRecovery.conservative(), BUDGET);
        assertTrue(resilience.tryAcquire());
        assertFalse(resilience.tryAcquire());
        resilience.release();
        assertTrue(resilience.tryAcquire());
    }

    @Test
    void unknownWriteOutcomeFailsClosedAndRetryLimitFails() {
        RuntimeAdapterResilience resilience = new RuntimeAdapterResilience(
                readyHealth(), RuntimeAdapterRecovery.conservative(), BUDGET);
        assertEquals(RuntimeAdapterRecovery.RecoveryDecision.FAIL,
                resilience.onFailure(new RuntimeAdapterRecovery.FailureContext(
                        "adapter", "operation", true, false, 1, "UNKNOWN")));
        assertEquals(RuntimeAdapterStateMachine.State.FAILED, resilience.snapshot().state());
    }

    @Test
    void retryTransitionsThroughRecoveryAndBackToReady() {
        RuntimeAdapterResilience resilience = new RuntimeAdapterResilience(
                readyHealth(), RuntimeAdapterRecovery.conservative(), BUDGET);
        assertEquals(RuntimeAdapterRecovery.RecoveryDecision.RETRY,
                resilience.onFailure(new RuntimeAdapterRecovery.FailureContext(
                        "adapter", "probe", true, true, 1, "TIMEOUT")));
        assertEquals(RuntimeAdapterStateMachine.State.RECOVERING, resilience.snapshot().state());
        resilience.recovered();
        assertEquals(RuntimeAdapterStateMachine.State.READY, resilience.snapshot().state());
    }

    @Test
    void stateMachineRejectsIllegalTransitionAndCloses() {
        RuntimeAdapterStateMachine machine = new RuntimeAdapterStateMachine("adapter");
        assertThrows(IllegalStateException.class,
                () -> machine.transition(RuntimeAdapterStateMachine.State.CLOSE));
        machine.transition(RuntimeAdapterStateMachine.State.DRAIN);
        machine.transition(RuntimeAdapterStateMachine.State.STOP);
        machine.transition(RuntimeAdapterStateMachine.State.CLOSE);
        assertEquals(RuntimeAdapterStateMachine.State.CLOSE, machine.snapshot().state());
    }

    private static RuntimeAdapterHealth readyHealth() {
        RuntimeAdapterHealth.HealthSnapshot snapshot = new RuntimeAdapterHealth.HealthSnapshot(
                "adapter", RuntimeAdapterHealth.Status.READY, "READY",
                Instant.parse("2026-01-01T00:00:00Z"), Duration.ZERO);
        return new RuntimeAdapterHealth() {
            @Override
            public HealthSnapshot snapshot() {
                return snapshot;
            }

            @Override
            public java.util.concurrent.CompletionStage<HealthSnapshot> probe(
                    final Duration timeout) {
                return CompletableFuture.completedFuture(snapshot);
            }
        };
    }
}
