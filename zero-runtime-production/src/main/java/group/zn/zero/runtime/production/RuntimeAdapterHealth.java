package group.zn.zero.runtime.production;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Common periodic health contract for production adapters. */
public interface RuntimeAdapterHealth {
    HealthSnapshot snapshot();

    CompletionStage<HealthSnapshot> probe(Duration timeout);

    enum Status {
        READY,
        DEGRADED,
        UNAVAILABLE
    }

    record HealthSnapshot(
            String adapterName,
            Status status,
            String code,
            Instant observedAt,
            Duration latency) {
        public HealthSnapshot {
            requireText(adapterName, "adapterName", 128);
            Objects.requireNonNull(status, "status");
            requireText(code, "code", 128);
            Objects.requireNonNull(observedAt, "observedAt");
            Objects.requireNonNull(latency, "latency");
            if (latency.isNegative()) {
                throw new IllegalArgumentException("latency must not be negative");
            }
        }

        private static void requireText(
                final String value, final String name, final int max) {
            if (value == null || value.isBlank() || value.length() > max
                    || value.chars().anyMatch(Character::isISOControl)) {
                throw new IllegalArgumentException(name + " is invalid");
            }
        }
    }
}
