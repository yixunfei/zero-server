package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.lifecycle.Lifecycle;
import group.zn.zero.core.lifecycle.LifecycleState;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import group.zn.zero.runtime.support.RecordingLifecycle;
import group.zn.zero.runtime.support.RecordingResource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * single-use、清理重试和并发边界测试。
 */
class GameRuntimeStateMachineTest {

    private static final ComponentId ALPHA_ID = ComponentId.of("state.provider.alpha");
    private static final ComponentId BETA_ID = ComponentId.of("state.provider.beta");
    private static final ComponentKey<String> ALPHA = ComponentKey.single("state.alpha", String.class);
    private static final ComponentKey<String> BETA = ComponentKey.single("state.beta", String.class);

    @Test
    void closeBeforeStartShouldCloseResourcesAndRejectLaterStart() {
        List<String> events = new ArrayList<>();
        GameRuntime runtime = twoComponentRuntime(
                events,
                RecordingLifecycle.healthy("alpha", events),
                RecordingLifecycle.healthy("beta", events),
                RecordingResource.healthy("alpha", events),
                RecordingResource.healthy("beta", events));

        runtime.close();
        runtime.close();
        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(List.of("close:beta", "close:alpha"), events);
        assertEquals(RuntimeErrorCode.RUNTIME_REUSE_REJECTED, failure.errorCode());
        assertEquals(RuntimeState.CLOSED, runtime.runtimeState());
    }

    @Test
    void everySecondStartRequestShouldBeRejected() {
        List<String> events = new ArrayList<>();
        GameRuntime runtime = oneComponentRuntime(
                events,
                RecordingLifecycle.healthy("alpha", events),
                RecordingResource.healthy("alpha", events));
        runtime.start();

        RuntimeAssemblyException secondStart = assertThrows(RuntimeAssemblyException.class, runtime::start);
        runtime.stop();
        RuntimeAssemblyException afterStop = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_REUSE_REJECTED, secondStart.errorCode());
        assertEquals(RuntimeErrorCode.RUNTIME_REUSE_REJECTED, afterStop.errorCode());
        runtime.close();
    }

    @Test
    void failedStopAndCloseItemsShouldRetryWithoutRepeatingSuccessfulCleanup() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle alphaLifecycle = new RecordingLifecycle(
                "alpha", events, 0, 1, "alpha-stop-secret");
        RecordingLifecycle betaLifecycle = new RecordingLifecycle(
                "beta", events, 0, 1, "beta-stop-secret");
        RecordingResource alphaResource = new RecordingResource(
                "alpha", events, 1, "alpha-close-secret");
        RecordingResource betaResource = RecordingResource.healthy("beta", events);
        GameRuntime runtime = twoComponentRuntime(
                events, alphaLifecycle, betaLifecycle, alphaResource, betaResource);
        runtime.start();
        events.clear();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::stop);

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_STOP_FAILED, failure.errorCode());
        assertEquals(2, failure.getSuppressed().length);
        assertEquals(List.of("stop:beta", "stop:alpha", "close:beta", "close:alpha"), events);
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
        assertEquals(1, runtime.report().pendingResourceCloseCount());
        events.clear();

        runtime.close();

        assertEquals(List.of("stop:beta", "stop:alpha", "close:alpha"), events);
        assertEquals(1, betaResource.closeCount());
        assertEquals(2, alphaResource.closeCount());
        assertEquals(RuntimeState.CLOSED, runtime.runtimeState());
        assertEquals(0, runtime.report().pendingResourceCloseCount());
    }

    @Test
    void concurrentCloseShouldSerializeBehindStartAndThenCleanlyStop() throws Exception {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        BlockingLifecycle lifecycle = new BlockingLifecycle(events, startEntered, releaseStart);
        GameRuntime runtime = oneComponentRuntime(
                events, lifecycle, RecordingResource.healthy("alpha", events));

        CompletableFuture<Void> start = CompletableFuture.runAsync(runtime::start);
        assertTrue(startEntered.await(2, TimeUnit.SECONDS));
        CompletableFuture<Void> close = CompletableFuture.runAsync(runtime::close);
        releaseStart.countDown();
        start.get(2, TimeUnit.SECONDS);
        close.get(2, TimeUnit.SECONDS);

        assertEquals(RuntimeState.CLOSED, runtime.runtimeState());
        assertEquals(List.of("start:alpha", "stop:alpha", "close:alpha"), events);
    }

    private static GameRuntime oneComponentRuntime(
            final List<String> events,
            final Lifecycle lifecycle,
            final RecordingResource resource) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(ALPHA_ID).provide(ALPHA).build();
        FakeRuntimeProvider provider = provider(descriptor, ALPHA, "alpha", lifecycle, resource);
        ComponentCatalog catalog = ComponentCatalog.builder().register("state-test", provider).build();
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(ALPHA)
                .select(ALPHA, ALPHA_ID, "state-test")
                .build();
    }

    private static GameRuntime twoComponentRuntime(
            final List<String> events,
            final Lifecycle alphaLifecycle,
            final Lifecycle betaLifecycle,
            final RecordingResource alphaResource,
            final RecordingResource betaResource) {
        ComponentDescriptor alphaDescriptor = ComponentDescriptor.builder(ALPHA_ID).provide(ALPHA).build();
        ComponentDescriptor betaDescriptor = ComponentDescriptor.builder(BETA_ID)
                .provide(BETA)
                .require(ALPHA)
                .build();
        FakeRuntimeProvider alpha = provider(
                alphaDescriptor, ALPHA, "alpha", alphaLifecycle, alphaResource);
        FakeRuntimeProvider beta = new FakeRuntimeProvider(betaDescriptor, context -> {
            context.require(ALPHA);
            context.resources().register(betaResource);
            return ComponentContribution.builder()
                    .bind(BETA, "beta")
                    .lifecycle(betaLifecycle)
                    .build();
        });
        ComponentCatalog catalog = ComponentCatalog.builder()
                .register("state-test", alpha)
                .register("state-test", beta)
                .build();
        return RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(BETA)
                .select(ALPHA, ALPHA_ID, "state-test")
                .select(BETA, BETA_ID, "state-test")
                .build();
    }

    private static FakeRuntimeProvider provider(
            final ComponentDescriptor descriptor,
            final ComponentKey<String> key,
            final String value,
            final Lifecycle lifecycle,
            final RecordingResource resource) {
        return new FakeRuntimeProvider(descriptor, context -> {
            context.resources().register(resource);
            return ComponentContribution.builder()
                    .bind(key, value)
                    .lifecycle(lifecycle)
                    .build();
        });
    }

    /** 在 start 内等待测试信号的 lifecycle。 */
    private static final class BlockingLifecycle implements Lifecycle {

        private final List<String> events;
        private final CountDownLatch startEntered;
        private final CountDownLatch releaseStart;
        private volatile LifecycleState state = LifecycleState.NEW;

        private BlockingLifecycle(
                final List<String> events,
                final CountDownLatch startEntered,
                final CountDownLatch releaseStart) {
            this.events = events;
            this.startEntered = startEntered;
            this.releaseStart = releaseStart;
        }

        @Override
        public LifecycleState state() {
            return state;
        }

        @Override
        public void start() {
            events.add("start:alpha");
            startEntered.countDown();
            try {
                if (!releaseStart.await(2, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("test start release timed out");
                }
            } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("test start interrupted");
            }
            state = LifecycleState.RUNNING;
        }

        @Override
        public void stop() {
            events.add("stop:alpha");
            state = LifecycleState.STOPPED;
        }
    }
}
