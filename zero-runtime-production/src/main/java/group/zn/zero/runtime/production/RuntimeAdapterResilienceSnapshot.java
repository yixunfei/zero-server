package group.zn.zero.runtime.production;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

/** Immutable, low-cardinality resilience diagnostics safe for health endpoints. */
public record RuntimeAdapterResilienceSnapshot(
        String adapterName,
        RuntimeAdapterStateMachine.State state,
        RuntimeAdapterHealth.Status healthStatus,
        String healthCode,
        int inFlight,
        int maxInFlight,
        int lastAttempt,
        RuntimeAdapterRecovery.RecoveryDecision lastDecision,
        Duration operationTimeout,
        Duration nextBackoff,
        Instant observedAt) {
    public RuntimeAdapterResilienceSnapshot {
        requireText(adapterName, "adapterName", 128);
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(healthStatus, "healthStatus");
        requireText(healthCode, "healthCode", 128);
        if (inFlight < 0 || maxInFlight <= 0 || inFlight > maxInFlight || lastAttempt < 0) {
            throw new IllegalArgumentException("invalid resilience counters");
        }
        Objects.requireNonNull(lastDecision, "lastDecision");
        positive(operationTimeout, "operationTimeout");
        positive(nextBackoff, "nextBackoff");
        Objects.requireNonNull(observedAt, "observedAt");
    }

    private static void requireText(final String value, final String name, final int max) {
        if (value == null || value.isBlank() || value.length() > max
                || value.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(name + " is invalid");
        }
    }

    private static void positive(final Duration value, final String name) {
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
