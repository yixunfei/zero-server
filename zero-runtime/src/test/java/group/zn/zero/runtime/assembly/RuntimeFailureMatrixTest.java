package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import group.zn.zero.runtime.support.RecordingLifecycle;
import group.zn.zero.runtime.support.RecordingResource;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/**
 * create、contribution、start、health、stop 和 resource close 失败矩阵。
 */
class RuntimeFailureMatrixTest {

    private static final ComponentId ALPHA_ID = ComponentId.of("failure.provider.alpha");
    private static final ComponentId BETA_ID = ComponentId.of("failure.provider.beta");
    private static final ComponentId GAMMA_ID = ComponentId.of("failure.provider.gamma");
    private static final ComponentKey<String> ALPHA = ComponentKey.single("failure.alpha", String.class);
    private static final ComponentKey<String> BETA = ComponentKey.single("failure.beta", String.class);
    private static final ComponentKey<String> GAMMA = ComponentKey.single("failure.gamma", String.class);

    @Test
    void createFailureShouldRollbackEveryAcquiredResourceInReverseOrder() {
        List<String> events = new ArrayList<>();
        FakeRuntimeProvider alpha = provider(ALPHA_ID, ALPHA, null, context -> {
            context.resources().register(RecordingResource.healthy("alpha", events));
            return contribution(ALPHA, "alpha", null);
        });
        FakeRuntimeProvider beta = provider(BETA_ID, BETA, ALPHA, context -> {
            context.resources().register(RecordingResource.healthy("beta", events));
            throw new IllegalStateException("raw-create-secret");
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(alpha, beta), BETA, BETA_ID).build());

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED, failure.errorCode());
        assertEquals(List.of("close:beta", "close:alpha"), events);
        assertNull(failure.getCause());
        assertFalse(failure.toString().contains("raw-create-secret"));
        assertEquals(RuntimeState.FAILED, failure.report().orElseThrow().state());
        assertEquals(0, failure.report().orElseThrow().pendingResourceCloseCount());
    }

    @Test
    void firstMiddleAndLastCreateFailureShouldRollbackOnlyAcquiredResources() {
        List<List<String>> expected = List.of(
                List.of("close:alpha"),
                List.of("close:beta", "close:alpha"),
                List.of("close:gamma", "close:beta", "close:alpha"));
        for (int failureIndex = 0; failureIndex < 3; failureIndex++) {
            List<String> events = new ArrayList<>();
            FakeRuntimeProvider alpha = positionalProvider(
                    0, failureIndex, ALPHA_ID, ALPHA, null, events, "alpha");
            FakeRuntimeProvider beta = positionalProvider(
                    1, failureIndex, BETA_ID, BETA, ALPHA, events, "beta");
            FakeRuntimeProvider gamma = positionalProvider(
                    2, failureIndex, GAMMA_ID, GAMMA, BETA, events, "gamma");

            RuntimeAssemblyException failure = assertThrows(
                    RuntimeAssemblyException.class,
                    () -> assembler(catalog(alpha, beta, gamma), GAMMA, GAMMA_ID).build());

            assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_CREATE_FAILED, failure.errorCode());
            assertEquals(expected.get(failureIndex), events);
        }
    }

    @Test
    void invalidContributionShouldRollbackBeforePublishingBindings() {
        List<String> events = new ArrayList<>();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(ALPHA_ID).provide(ALPHA).build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> {
            context.resources().register(RecordingResource.healthy("alpha", events));
            return ComponentContribution.builder().bind(BETA, "wrong-key").build();
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(provider), ALPHA, ALPHA_ID).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID, failure.errorCode());
        assertEquals(List.of("close:alpha"), events);
    }

    @Test
    @SuppressWarnings({"rawtypes", "unchecked"})
    void contributionTypeMismatchShouldFailAtBuildBoundary() {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(ALPHA_ID).provide(ALPHA).build();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> {
            ComponentContribution.Builder builder = ComponentContribution.builder();
            builder.bind((ComponentKey) ALPHA, 42);
            return builder.build();
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(provider), ALPHA, ALPHA_ID).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID, failure.errorCode());
    }

    @Test
    void undeclaredContextAccessShouldBeRejectedAsInvalidContribution() {
        FakeRuntimeProvider provider = provider(ALPHA_ID, ALPHA, null, context -> {
            context.require(BETA);
            return contribution(ALPHA, "alpha", null);
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(provider), ALPHA, ALPHA_ID).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID, failure.errorCode());
    }

    @Test
    void duplicateResourceIdentityShouldCloseSharedResourceOnlyOnce() {
        List<String> events = new ArrayList<>();
        RecordingResource shared = RecordingResource.healthy("shared", events);
        FakeRuntimeProvider alpha = provider(ALPHA_ID, ALPHA, null, context -> {
            context.resources().register(shared);
            return contribution(ALPHA, "alpha", null);
        });
        FakeRuntimeProvider beta = provider(BETA_ID, BETA, ALPHA, context -> {
            context.resources().register(shared);
            return contribution(BETA, "beta", null);
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(alpha, beta), BETA, BETA_ID).build());

        assertEquals(RuntimeErrorCode.RUNTIME_CONTRIBUTION_INVALID, failure.errorCode());
        assertEquals(1, shared.closeCount());
    }

    @Test
    void middleStartFailureShouldStopOnlySuccessfullyStartedComponents() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle alphaLifecycle = RecordingLifecycle.healthy("alpha", events);
        RecordingLifecycle betaLifecycle = new RecordingLifecycle(
                "beta", events, 1, 0, "raw-start-secret");
        RecordingLifecycle gammaLifecycle = RecordingLifecycle.healthy("gamma", events);
        FakeRuntimeProvider alpha = lifecycleProvider(ALPHA_ID, ALPHA, null, alphaLifecycle, events);
        FakeRuntimeProvider beta = lifecycleProvider(BETA_ID, BETA, ALPHA, betaLifecycle, events);
        FakeRuntimeProvider gamma = lifecycleProvider(GAMMA_ID, GAMMA, BETA, gammaLifecycle, events);
        GameRuntime runtime = assembler(catalog(alpha, beta, gamma), GAMMA, GAMMA_ID).build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_START_FAILED, failure.errorCode());
        assertEquals(List.of(
                "start:alpha", "start:beta", "stop:alpha",
                "close:gamma", "close:beta", "close:alpha"), events);
        assertFalse(events.contains("start:gamma"));
        assertFalse(failure.toString().contains("raw-start-secret"));
        assertEquals(RuntimeState.FAILED, runtime.runtimeState());
    }

    @Test
    void unhealthyStartupProbeShouldStopItsStartedLifecycleAndDependencies() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle alphaLifecycle = RecordingLifecycle.healthy("alpha", events);
        RecordingLifecycle betaLifecycle = RecordingLifecycle.healthy("beta", events);
        FakeRuntimeProvider alpha = lifecycleProvider(ALPHA_ID, ALPHA, null, alphaLifecycle, events);
        ComponentDescriptor betaDescriptor = ComponentDescriptor.builder(BETA_ID)
                .provide(BETA)
                .require(ALPHA)
                .health(HealthPhase.STARTUP)
                .build();
        FakeRuntimeProvider beta = new FakeRuntimeProvider(betaDescriptor, context -> {
            context.require(ALPHA);
            context.resources().register(RecordingResource.healthy("beta", events));
            return ComponentContribution.builder()
                    .bind(BETA, "beta")
                    .lifecycle(betaLifecycle)
                    .healthProbe(HealthPhase.STARTUP, request -> CompletableFuture.completedFuture(
                            new HealthResult(HealthStatus.UNHEALTHY, "dependency-down")))
                    .build();
        });
        GameRuntime runtime = assembler(catalog(alpha, beta), BETA, BETA_ID).build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_COMPONENT_HEALTH_FAILED, failure.errorCode());
        assertEquals(List.of(
                "start:alpha", "start:beta", "stop:beta", "stop:alpha",
                "close:beta", "close:alpha"), events);
        assertEquals(HealthStatus.UNHEALTHY,
                runtime.healthSnapshot().components().getFirst().result().status());
    }

    @Test
    void startupHealthTimeoutShouldCancelProbeBeforeRollback() {
        List<String> events = new ArrayList<>();
        CompletableFuture<HealthResult> health = new CompletableFuture<>() {
            @Override
            public boolean cancel(final boolean mayInterruptIfRunning) {
                super.cancel(mayInterruptIfRunning);
                throw new AssertionError("raw-cancel-secret");
            }
        };
        ComponentDescriptor descriptor = ComponentDescriptor.builder(ALPHA_ID)
                .provide(ALPHA)
                .health(HealthPhase.STARTUP)
                .build();
        FakeRuntimeProvider alpha = new FakeRuntimeProvider(descriptor, context -> {
            context.resources().register(RecordingResource.healthy("alpha", events));
            return ComponentContribution.builder()
                    .bind(ALPHA, "alpha")
                    .healthProbe(HealthPhase.STARTUP, request -> health)
                    .build();
        });
        GameRuntime runtime = assembler(catalog(alpha), ALPHA, ALPHA_ID)
                .startupTimeout(Duration.ofMillis(100L))
                .build();

        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, runtime::start);

        assertEquals(RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT, failure.errorCode());
        assertTrue(health.isCancelled());
        assertEquals(List.of("close:alpha"), events);
        assertFalse(failure.toString().contains("raw-cancel-secret"));
    }

    @Test
    void controlledClockTimeoutShouldRollbackCreateWithoutSleeping() {
        List<String> events = new ArrayList<>();
        AtomicLong clock = new AtomicLong();
        FakeRuntimeProvider alpha = provider(ALPHA_ID, ALPHA, null, context -> {
            context.resources().register(RecordingResource.healthy("alpha", events));
            clock.set(20L);
            return contribution(ALPHA, "alpha", null);
        });

        RuntimeAssemblyException failure = assertThrows(
                RuntimeAssemblyException.class,
                () -> assembler(catalog(alpha), ALPHA, ALPHA_ID)
                        .assemblyTimeout(Duration.ofNanos(10L))
                        .monotonicClock(clock::get)
                        .build());

        assertEquals(RuntimeErrorCode.RUNTIME_STARTUP_TIMEOUT, failure.errorCode());
        assertEquals(List.of("close:alpha"), events);
    }

    @Test
    void assemblyAndStartPhaseDeadlinesShouldUseIndependentOrigins() {
        AtomicLong clock = new AtomicLong();
        AtomicLong assemblyRemaining = new AtomicLong();
        AtomicLong startupRemaining = new AtomicLong();
        ComponentDescriptor descriptor = ComponentDescriptor.builder(ALPHA_ID)
                .provide(ALPHA)
                .health(HealthPhase.STARTUP)
                .build();
        FakeRuntimeProvider alpha = new FakeRuntimeProvider(descriptor, context -> {
            clock.set(90L);
            assemblyRemaining.set(context.assemblyDeadline().remaining().toNanos());
            return ComponentContribution.builder()
                    .bind(ALPHA, "alpha")
                    .healthProbe(HealthPhase.STARTUP, request -> {
                        startupRemaining.set(request.timeout().toNanos());
                        clock.set(1_005L);
                        return CompletableFuture.completedFuture(HealthResult.healthy("ready"));
                    })
                    .build();
        });
        GameRuntime runtime = assembler(catalog(alpha), ALPHA, ALPHA_ID)
                .assemblyTimeout(Duration.ofNanos(100L))
                .startupTimeout(Duration.ofNanos(10L))
                .monotonicClock(clock::get)
                .build();

        clock.set(1_000L);
        runtime.start();

        assertEquals(10L, assemblyRemaining.get());
        assertEquals(10L, startupRemaining.get());
        assertEquals(RuntimeState.RUNNING, runtime.runtimeState());
        runtime.close();
    }

    private static FakeRuntimeProvider lifecycleProvider(
            final ComponentId id,
            final ComponentKey<String> key,
            final ComponentKey<String> dependency,
            final RecordingLifecycle lifecycle,
            final List<String> events) {
        return provider(id, key, dependency, context -> {
            if (dependency != null) {
                context.require(dependency);
            }
            context.resources().register(RecordingResource.healthy(id.value().substring(id.value().lastIndexOf('.') + 1), events));
            return contribution(key, id.value(), lifecycle);
        });
    }

    private static FakeRuntimeProvider positionalProvider(
            final int position,
            final int failurePosition,
            final ComponentId id,
            final ComponentKey<String> key,
            final ComponentKey<String> dependency,
            final List<String> events,
            final String name) {
        return provider(id, key, dependency, context -> {
            if (dependency != null) {
                context.require(dependency);
            }
            context.resources().register(RecordingResource.healthy(name, events));
            if (position == failurePosition) {
                throw new IllegalStateException("raw positional create failure");
            }
            return contribution(key, name, null);
        });
    }

    private static FakeRuntimeProvider provider(
            final ComponentId id,
            final ComponentKey<String> key,
            final ComponentKey<String> dependency,
            final FakeRuntimeProvider.Creator creator) {
        ComponentDescriptor.Builder descriptor = ComponentDescriptor.builder(id).provide(key);
        if (dependency != null) {
            descriptor.require(dependency);
        }
        return new FakeRuntimeProvider(descriptor.build(), creator);
    }

    private static ComponentContribution contribution(
            final ComponentKey<String> key,
            final String value,
            final RecordingLifecycle lifecycle) {
        ComponentContribution.Builder builder = ComponentContribution.builder().bind(key, value);
        if (lifecycle != null) {
            builder.lifecycle(lifecycle);
        }
        return builder.build();
    }

    private static RuntimeAssembler.Builder assembler(
            final ComponentCatalog catalog,
            final ComponentKey<String> root,
            final ComponentId rootId) {
        RuntimeAssembler.Builder builder = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(root)
                .select(root, rootId, "test");
        if (!root.equals(ALPHA) && catalog.componentIds().contains(ALPHA_ID)) {
            builder.select(ALPHA, ALPHA_ID, "test");
        }
        if (root.equals(GAMMA) && catalog.componentIds().contains(BETA_ID)) {
            builder.select(BETA, BETA_ID, "test");
        }
        return builder;
    }

    private static ComponentCatalog catalog(final FakeRuntimeProvider... providers) {
        ComponentCatalog.Builder builder = ComponentCatalog.builder();
        for (FakeRuntimeProvider provider : providers) {
            builder.register("failure-test", provider);
        }
        return builder.build();
    }
}
