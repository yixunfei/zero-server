package group.zn.zero.starter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import org.junit.jupiter.api.Test;

/** Local Starter integration tests for the neutral runtime rollback contract. */
class LocalRuntimeRollbackTest {

    @Test
    void startFailureShouldRollbackOnlyStartedComponentsAndCloseExecutors() {
        List<String> steps = new ArrayList<>();
        RecordingLifecycle first = RecordingLifecycle.healthy("first", steps);
        RecordingLifecycle second = RecordingLifecycle.healthy("second", steps);
        RecordingLifecycle failing = RecordingLifecycle.startFailure("failing", steps);
        RecordingLifecycle neverStarted = RecordingLifecycle.healthy("never", steps);
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("starter-rollback", 1);
        GameRuntime runtime = LocalRuntime.builder(config(), new group.zn.zero.log.InMemoryLogSink(), executors)
                .addApplicationLifecycle(ComponentId.of("test.rollback.a-first"), first)
                .addApplicationLifecycle(ComponentId.of("test.rollback.b-second"), second)
                .addApplicationLifecycle(ComponentId.of("test.rollback.c-failing"), failing)
                .addApplicationLifecycle(ComponentId.of("test.rollback.d-never"), neverStarted)
                .build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertSame(RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED, failure.errorCode());
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
        assertEquals(List.of(
                "start:first", "start:second", "start:failing",
                "stop:second", "stop:first"), steps);
        assertFalse(failure.toString().contains("raw-start-secret"));
        assertExecutorShutdown(executors.logicExecutor());
        assertExecutorShutdown(executors.actorExecutor());
        assertExecutorShutdown(executors.remoteIoExecutor());
        assertExecutorShutdown(executors.backgroundExecutor());
    }

    @Test
    void repeatedStopShouldBeIdempotent() {
        List<String> steps = new ArrayList<>();
        RecordingLifecycle component = RecordingLifecycle.healthy("component", steps);
        GameRuntime runtime = runtimeWith(component);

        runtime.start();
        runtime.stop();
        runtime.stop();

        assertEquals(List.of("start:component", "stop:component"), steps);
        assertEquals(RuntimeState.STOPPED, runtime.runtimeState());
    }

    @Test
    void stopShouldContinueInReverseOrderAndAggregateSafeFailures() {
        List<String> steps = new ArrayList<>();
        GameRuntime runtime = LocalRuntime.builder(config())
                .addApplicationLifecycle(
                        ComponentId.of("test.stop.a-first"), RecordingLifecycle.healthy("first", steps))
                .addApplicationLifecycle(
                        ComponentId.of("test.stop.b-second"), RecordingLifecycle.stopFailure("second", steps))
                .addApplicationLifecycle(
                        ComponentId.of("test.stop.c-third"), RecordingLifecycle.stopFailure("third", steps))
                .build();
        runtime.start();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::stop);

        assertSame(RuntimeErrorCode.RUNTIME_COMPONENT_STOP_FAILED, failure.errorCode());
        assertEquals(1, failure.getSuppressed().length);
        assertEquals(List.of(
                "start:first", "start:second", "start:third",
                "stop:third", "stop:second", "stop:first"), steps);
        assertFalse(failure.toString().contains("raw-stop-secret"));
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
    }

    @Test
    void closeBeforeStartShouldReleaseOwnedExecutors() {
        ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("starter-close", 1);
        GameRuntime runtime = LocalRuntime.builder(config(), new group.zn.zero.log.InMemoryLogSink(), executors)
                .build();

        runtime.close();

        assertEquals(RuntimeState.CLOSED, runtime.runtimeState());
        assertExecutorShutdown(executors.logicExecutor());
        assertExecutorShutdown(executors.actorExecutor());
        assertExecutorShutdown(executors.remoteIoExecutor());
        assertExecutorShutdown(executors.backgroundExecutor());
    }

    private static GameRuntime runtimeWith(final Lifecycle lifecycle) {
        return LocalRuntime.builder(config())
                .addApplicationLifecycle(ComponentId.of("test.lifecycle.component"), lifecycle)
                .build();
    }

    private static MapZeroConfig config() {
        return new MapZeroConfig(Map.of("zero.mode", "test"));
    }

    private static void assertExecutorShutdown(final java.util.concurrent.Executor executor) {
        assertTrue(((ExecutorService) executor).isShutdown());
    }

    private static final class RecordingLifecycle implements Lifecycle {

        private final String name;
        private final List<String> steps;
        private final boolean failStart;
        private final boolean failStop;
        private LifecycleState state = LifecycleState.NEW;

        private RecordingLifecycle(
                final String name,
                final List<String> steps,
                final boolean failStart,
                final boolean failStop) {
            this.name = name;
            this.steps = steps;
            this.failStart = failStart;
            this.failStop = failStop;
        }

        static RecordingLifecycle healthy(final String name, final List<String> steps) {
            return new RecordingLifecycle(name, steps, false, false);
        }

        static RecordingLifecycle startFailure(final String name, final List<String> steps) {
            return new RecordingLifecycle(name, steps, true, false);
        }

        static RecordingLifecycle stopFailure(final String name, final List<String> steps) {
            return new RecordingLifecycle(name, steps, false, true);
        }

        @Override
        public LifecycleState state() {
            return state;
        }

        @Override
        public void start() {
            steps.add("start:" + name);
            if (failStart) {
                state = LifecycleState.FAILED;
                throw new IllegalStateException("raw-start-secret");
            }
            state = LifecycleState.RUNNING;
        }

        @Override
        public void stop() {
            steps.add("stop:" + name);
            if (failStop) {
                state = LifecycleState.FAILED;
                throw new IllegalStateException("raw-stop-secret");
            }
            state = LifecycleState.STOPPED;
        }
    }
}
