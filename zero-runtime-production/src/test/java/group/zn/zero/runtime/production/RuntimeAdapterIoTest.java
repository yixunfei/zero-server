package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.io.IOException;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Runtime remote IO admission, failure and recovery contract tests. */
class RuntimeAdapterIoTest {
    private static final RuntimeAdapterBudget BUDGET = new RuntimeAdapterBudget(
            Duration.ofSeconds(1), Duration.ofSeconds(1), 1, 0,
            Duration.ofMillis(10), Duration.ofSeconds(1), 2.0d);

    @Test void unknownWriteDegradesAndProbeRecoversWithoutReplaying() {
        AtomicInteger writes = new AtomicInteger();
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("adapter-test")) {
            RuntimeAdapterIo monitor = monitor(executors);
                ProductionAdapterException failure = assertThrows(ProductionAdapterException.class,
                        () -> monitor.call(true, () -> {
                            writes.incrementAndGet();
                            throw new IllegalStateException(new IOException("connection lost"));
                        }));
                assertEquals(ProductionAdapterErrorCode.WRITE_OUTCOME_UNKNOWN, failure.errorCode());
                assertEquals(RuntimeAdapterStateMachine.State.DEGRADED, monitor.snapshot().state());
                assertEquals(ProductionAdapterErrorCode.OPERATION_REJECTED,
                        assertThrows(ProductionAdapterException.class,
                                () -> monitor.call(false, () -> "value")).errorCode());
                monitor.probe().toCompletableFuture().join();
                assertEquals(RuntimeAdapterStateMachine.State.READY, monitor.snapshot().state());
                assertEquals(1, writes.get());
        }
    }

    @Test void stoppedMonitorCannotBecomeReadyAfterLateProbe() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("adapter-test")) {
            RuntimeAdapterIo monitor = monitor(executors);
            monitor.start();
            monitor.stop();
            monitor.probe().toCompletableFuture().join();
            assertEquals(RuntimeAdapterStateMachine.State.CLOSE, monitor.snapshot().state());
        }
    }

    @Test void asyncAdmissionIsHeldUntilCompletion() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("adapter-test")) {
            RuntimeAdapterIo monitor = monitor(executors);
            CompletableFuture<String> pending = new CompletableFuture<>();
            var result = monitor.observe(false, () -> pending);
            assertEquals(1, monitor.snapshot().inFlight());
            assertEquals(ProductionAdapterErrorCode.OPERATION_REJECTED,
                    assertThrows(ProductionAdapterException.class,
                            () -> monitor.call(false, () -> "second")).errorCode());
            pending.complete("first");
            assertEquals("first", result.toCompletableFuture().join());
            assertEquals(0, monitor.snapshot().inFlight());
            assertEquals("second", monitor.call(false, () -> "second"));
        }
    }

    @Test void asyncRemoteFailurePreservesReadFailureCode() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("adapter-test")) {
            RuntimeAdapterIo monitor = monitor(executors);
            CompletableFuture<String> pending = new CompletableFuture<>();
            var result = monitor.observe(false, () -> pending);
            pending.completeExceptionally(new IOException("private endpoint"));
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> result.toCompletableFuture().join());
            assertEquals(ProductionAdapterErrorCode.REMOTE_IO_FAILED,
                    ((ProductionAdapterException) failure.getCause()).errorCode());
            assertEquals(RuntimeAdapterStateMachine.State.DEGRADED, monitor.snapshot().state());
        }
    }

    @Test void deterministicSqlWriteFailureDoesNotDegrade() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.remoteIo("adapter-test")) {
            RuntimeAdapterIo monitor = monitor(executors);
            assertThrows(IllegalStateException.class, () -> monitor.call(true,
                    () -> { throw new IllegalStateException(new SQLException("duplicate", "23505")); }));
            assertEquals(RuntimeAdapterStateMachine.State.READY, monitor.snapshot().state());
            assertEquals("ok", monitor.call(false, () -> "ok"));
        }
    }

    private RuntimeAdapterIo monitor(final ZeroRuntimeExecutors executors) {
        ProductionAdapterDiagnostic diagnostic = new ProductionAdapterDiagnostic("test.adapter",
                ZeroProductionAdapterState.CREATED, List.of(), List.of(), List.of(), List.of(), List.of());
        return new RuntimeAdapterIo(diagnostic, timeout -> { },
                RuntimeAdapterRemoteFailures::isRemoteIo, executors, BUDGET, Duration.ofHours(1));
    }
}
