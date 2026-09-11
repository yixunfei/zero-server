package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthRequest;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ProductionStartupBudget;
import group.zn.zero.runtime.production.ProductionStartupHealthProbe;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionAdapterStatus;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CompletionException;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Production 同步检查到中立 startup health 的桥接契约测试。 */
class ProductionStartupHealthProbeTest {

    private static final String SECRET = "PRODUCTION-STARTUP-HEALTH-SECRET";

    @Test
    void shouldUseSmallerRuntimeBudgetAndMarkHealthy() {
        ProductionAdapterDiagnostic diagnostic = diagnostic();
        AtomicReference<Duration> observedTimeout = new AtomicReference<>();
        ProductionStartupHealthProbe probe = new ProductionStartupHealthProbe(
                diagnostic,
                observedTimeout::set,
                new ProductionStartupBudget(Duration.ofSeconds(10), Duration.ofSeconds(3), () -> 0L));

        var result = probe.check(new HealthRequest(
                        HealthPhase.STARTUP,
                        Duration.ofSeconds(2),
                        Instant.EPOCH))
                .toCompletableFuture()
                .join();

        assertEquals(Duration.ofSeconds(2), observedTimeout.get());
        assertEquals(HealthStatus.HEALTHY, result.status());
        assertEquals(ZeroProductionAdapterState.HEALTHY, diagnostic.snapshot().state());
    }

    @Test
    void shouldSanitizeFailureAndUpdateDiagnosticBeforeReturningFailedStage() {
        ProductionAdapterDiagnostic diagnostic = diagnostic();
        ProductionStartupHealthProbe probe = new ProductionStartupHealthProbe(
                diagnostic,
                timeout -> {
                    throw new IllegalStateException(SECRET);
                },
                new ProductionStartupBudget(Duration.ofSeconds(10), Duration.ofSeconds(3), () -> 0L));

        CompletionException completion = assertThrows(
                CompletionException.class,
                () -> probe.check(new HealthRequest(
                                HealthPhase.STARTUP,
                                Duration.ofSeconds(2),
                                Instant.EPOCH))
                        .toCompletableFuture()
                        .join());
        ProductionAdapterException failure = assertInstanceOf(
                ProductionAdapterException.class,
                completion.getCause());
        ZeroProductionAdapterStatus status = diagnostic.snapshot();

        assertEquals(ProductionAdapterNames.ADAPTER_KAFKA_RPC, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP_HEALTH, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED, failure.errorCode());
        assertNull(failure.getCause());
        assertEquals(ZeroProductionAdapterState.FAILED, status.state());
        assertEquals(ProductionAdapterFailurePhase.STARTUP_HEALTH, status.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED, status.errorCode());
        assertFalse(stackTrace(failure).contains(SECRET));
    }

    private ProductionAdapterDiagnostic diagnostic() {
        ProductionAdapterDiagnostic diagnostic = new ProductionAdapterDiagnostic(
                ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                ZeroProductionAdapterState.ENABLED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        diagnostic.mark(ZeroProductionAdapterState.CREATED);
        return diagnostic;
    }

    private String stackTrace(final Throwable failure) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            failure.printStackTrace(printer);
        }
        return writer.toString();
    }
}
