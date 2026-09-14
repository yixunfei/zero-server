package group.zn.zero.runtime.production;

import java.util.Objects;

/** Explicit recovery policy for a production adapter failure. */
@FunctionalInterface
public interface RuntimeAdapterRecovery {
    RecoveryDecision onFailure(FailureContext context);

    record FailureContext(
            String adapterName,
            String phase,
            boolean retryable,
            boolean writeOutcomeKnown,
            int attempt,
            String failureCode) {
        public FailureContext {
            requireText(adapterName, "adapterName", 128);
            requireText(phase, "phase", 64);
            requireText(failureCode, "failureCode", 128);
            if (attempt < 1) {
                throw new IllegalArgumentException("attempt must be positive");
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

    enum RecoveryDecision {
        RETRY,
        REBUILD,
        FAIL,
        DRAIN,
        IGNORE
    }

    static RuntimeAdapterRecovery conservative() {
        return context -> {
            Objects.requireNonNull(context, "context");
            if (!context.retryable() || !context.writeOutcomeKnown()) {
                return RecoveryDecision.FAIL;
            }
            return RecoveryDecision.RETRY;
        };
    }
}
