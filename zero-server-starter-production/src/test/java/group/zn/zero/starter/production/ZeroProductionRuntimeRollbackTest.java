package group.zn.zero.starter.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.lifecycle.AbstractLifecycle;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.production.ProductionAdapterDiagnostic;
import group.zn.zero.runtime.production.ProductionAdapterErrorCode;
import group.zn.zero.runtime.production.ProductionAdapterException;
import group.zn.zero.runtime.production.ProductionAdapterFailurePhase;
import group.zn.zero.runtime.production.ProductionAdapterFailures;
import group.zn.zero.runtime.production.ProductionAdapterNames;
import group.zn.zero.runtime.production.ZeroProductionAdapterState;
import group.zn.zero.runtime.production.ZeroProductionRuntime;
import group.zn.zero.starter.LocalRuntime;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** {@link ZeroProductionRuntime} 的安全失败归因与 single-use 委托契约测试。 */
class ZeroProductionRuntimeRollbackTest {

    private static final String SECRET = "PAF1-RUNTIME-ROLLBACK-SECRET-SENTINEL";

    @Test
    void neutralStartFailureShouldMapToSafeProductionFailure() {
        ZeroProductionRuntime runtime = runtimeWith(
                new StartupFailureLifecycle(),
                List.of());

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);

        assertEquals("production-runtime", failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_FAILED, failure.errorCode());
        assertEquals(ProductionAdapterErrorCode.STARTUP_FAILED.message(), failure.message());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
        runtime.close();
    }

    @Test
    void closeBeforeStartShouldRejectLaterStart() {
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of());

        runtime.close();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(ProductionAdapterFailurePhase.STARTUP, failure.failurePhase());
        assertEquals(0, lifecycle.startCalls);
    }

    @Test
    void stoppedRuntimeShouldRejectRestart() {
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of());

        runtime.start();
        runtime.stop();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(1, lifecycle.startCalls);
        assertEquals(1, lifecycle.stopCalls);
    }

    @Test
    void runningRuntimeShouldRejectSecondStart() {
        CountingLifecycle lifecycle = new CountingLifecycle();
        ZeroProductionRuntime runtime = runtimeWith(lifecycle, List.of());

        runtime.start();
        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);
        runtime.close();

        assertSame(ProductionAdapterErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(1, lifecycle.startCalls);
        assertEquals(1, lifecycle.stopCalls);
    }

    @Test
    void adapterAttributionShouldSurviveNeutralRuntimeNormalization() {
        ProductionAdapterDiagnostic diagnostic = new ProductionAdapterDiagnostic(
                ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                ZeroProductionAdapterState.CREATED,
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of());
        ZeroProductionRuntime runtime = runtimeWith(
                new DiagnosticFailureLifecycle(diagnostic),
                List.of(diagnostic));

        ProductionAdapterException failure = assertThrows(
                ProductionAdapterException.class,
                runtime::start);

        assertEquals(ProductionAdapterNames.ADAPTER_KAFKA_RPC, failure.adapterName());
        assertEquals(ProductionAdapterFailurePhase.STARTUP_HEALTH, failure.failurePhase());
        assertSame(ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED, failure.errorCode());
        assertNull(failure.getCause());
        assertFalse(stackTrace(failure).contains(SECRET), stackTrace(failure));
        runtime.close();
    }

    private ZeroProductionRuntime runtimeWith(
            final Lifecycle lifecycle,
            final List<ProductionAdapterDiagnostic> diagnostics) {
        GameRuntime delegate = LocalRuntime
                .builder(new MapZeroConfig(Map.of("zero.mode", "test")))
                .addApplicationLifecycle(ComponentId.of("test.production.lifecycle"), lifecycle)
                .build();
        return new ZeroProductionRuntime("production", delegate, diagnostics);
    }

    private String stackTrace(final Throwable failure) {
        StringWriter output = new StringWriter();
        try (PrintWriter printer = new PrintWriter(output)) {
            failure.printStackTrace(printer);
        }
        return output.toString();
    }

    private static final class StartupFailureLifecycle extends AbstractLifecycle {

        @Override
        protected void doStart() {
            throw new IllegalStateException(SECRET);
        }
    }

    private static final class DiagnosticFailureLifecycle extends AbstractLifecycle {

        private final ProductionAdapterDiagnostic diagnostic;

        private DiagnosticFailureLifecycle(final ProductionAdapterDiagnostic diagnostic) {
            this.diagnostic = diagnostic;
        }

        @Override
        protected void doStart() {
            ProductionAdapterException failure = ProductionAdapterFailures.failure(
                    ProductionAdapterNames.ADAPTER_KAFKA_RPC,
                    ProductionAdapterFailurePhase.STARTUP_HEALTH,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED,
                    ProductionAdapterErrorCode.STARTUP_HEALTH_FAILED.message());
            diagnostic.fail(failure);
            throw new IllegalStateException(SECRET);
        }
    }

    private static final class CountingLifecycle extends AbstractLifecycle {

        private int startCalls;
        private int stopCalls;

        @Override
        protected void doStart() {
            startCalls++;
        }

        @Override
        protected void doStop() {
            stopCalls++;
        }
    }
}
