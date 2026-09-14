package group.zn.zero.runtime.production;

import java.util.Objects;

/** Explicit factory for composing an adapter's health, recovery and budget contracts. */
public final class RuntimeAdapterResilienceFactory {
    private RuntimeAdapterResilienceFactory() {
    }

    public static RuntimeAdapterResilience create(
            final RuntimeAdapterHealth health,
            final RuntimeAdapterRecovery recovery,
            final RuntimeAdapterBudget budget) {
        return new RuntimeAdapterResilience(
                Objects.requireNonNull(health, "health"),
                Objects.requireNonNull(recovery, "recovery"),
                Objects.requireNonNull(budget, "budget"));
    }
}
