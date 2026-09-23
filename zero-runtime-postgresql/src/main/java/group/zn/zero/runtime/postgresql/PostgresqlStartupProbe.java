package group.zn.zero.runtime.postgresql;

import group.zn.zero.data.postgresql.PostgresqlDataHealthCheck;
import group.zn.zero.data.postgresql.PostgresqlDriverSettings;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import java.time.Duration;

/** Startup probe with a temporary client bounded by the remaining startup budget. */
final class PostgresqlStartupProbe {
    private PostgresqlStartupProbe() {
    }

    static void check(final PostgresqlDriverSettings settings, final Duration timeout) {
        if (timeout.compareTo(Duration.ofSeconds(1)) < 0) {
            throw ProductionAdapterFailures.failure(ProductionAdapterNames.ADAPTER_POSTGRESQL_DATA,
                    ProductionAdapterFailurePhase.STARTUP_BUDGET,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED,
                    ProductionAdapterErrorCode.STARTUP_BUDGET_EXHAUSTED.message());
        }
        new PostgresqlDataHealthCheck(settings, timeout).checkOrThrow();
    }
}
