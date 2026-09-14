package group.zn.zero.runtime.production;

import java.time.Duration;
import java.util.Objects;

/** Bounded operation and retry budget for one production adapter. */
public record RuntimeAdapterBudget(
        Duration startupTimeout,
        Duration operationTimeout,
        int maxInFlight,
        int maxRetry,
        Duration initialBackoff,
        Duration maxBackoff,
        double backoffMultiplier) {
    public RuntimeAdapterBudget {
        positive(startupTimeout, "startupTimeout");
        positive(operationTimeout, "operationTimeout");
        positive(initialBackoff, "initialBackoff");
        positive(maxBackoff, "maxBackoff");
        if (maxBackoff.compareTo(initialBackoff) < 0) {
            throw new IllegalArgumentException("maxBackoff must not be less than initialBackoff");
        }
        if (maxInFlight <= 0 || maxRetry < 0) {
            throw new IllegalArgumentException("maxInFlight must be positive and maxRetry non-negative");
        }
        if (!Double.isFinite(backoffMultiplier) || backoffMultiplier < 1.0d) {
            throw new IllegalArgumentException("backoffMultiplier must be finite and at least one");
        }
    }

    public Duration backoffFor(final int attempt) {
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt must be positive");
        }
        long base = initialBackoff.toNanos();
        long limit = maxBackoff.toNanos();
        double value = base;
        for (int index = 1; index < attempt && value < limit; index++) {
            value = Math.min(limit, value * backoffMultiplier);
        }
        if (!Double.isFinite(value)) {
            return maxBackoff;
        }
        return Duration.ofNanos(Math.min(limit, Math.max(1L, (long) value)));
    }

    private static void positive(final Duration value, final String name) {
        Duration current = Objects.requireNonNull(value, name);
        if (current.isZero() || current.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
