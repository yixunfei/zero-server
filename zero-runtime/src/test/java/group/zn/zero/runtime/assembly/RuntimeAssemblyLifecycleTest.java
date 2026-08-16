package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.api.RuntimeState;
import group.zn.zero.runtime.diagnostics.RuntimePhaseOutcome;
import group.zn.zero.runtime.health.HealthPhase;
import group.zn.zero.runtime.health.HealthResult;
import group.zn.zero.runtime.health.HealthStatus;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.support.FakeRuntimeProvider;
import group.zn.zero.runtime.support.RecordingLifecycle;
import group.zn.zero.runtime.support.RecordingResource;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

/**
 * 成功装配、启动、健康和逆序关闭闭环测试。
 */
class RuntimeAssemblyLifecycleTest {

    private static final ComponentId DATA_ID = ComponentId.of("runtime.provider.data");
    private static final ComponentId SERVICE_ID = ComponentId.of("runtime.provider.service");
    private static final ComponentKey<String> DATA = ComponentKey.single("runtime.data", String.class);
    private static final ComponentKey<Service> SERVICE = ComponentKey.single("runtime.service", Service.class);
    private static final ComponentKey<String> UNSELECTED = ComponentKey.single(
            "runtime.unselected", String.class);

    @Test
    void runtimeShouldCreateStartHealthStopAndCloseInDeterministicOrder() {
        List<String> events = new ArrayList<>();
        RecordingLifecycle dataLifecycle = RecordingLifecycle.healthy("data", events);
        RecordingLifecycle serviceLifecycle = RecordingLifecycle.healthy("service", events);
        FakeRuntimeProvider data = dataProvider(events, dataLifecycle);
        FakeRuntimeProvider service = serviceProvider(events, serviceLifecycle, HealthResult.healthy("service-ready"));
        ComponentCatalog catalog = ComponentCatalog.builder()
                .register("test", service)
                .register("test", data)
                .build();

        GameRuntime runtime = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(SERVICE)
                .select(DATA, DATA_ID, "test")
                .select(SERVICE, SERVICE_ID, "test")
                .build();

        assertEquals(List.of("create:data", "create:service"), events);
        assertEquals("data-value", runtime.require(SERVICE).data());
        assertEquals("data-value", runtime.optional(SERVICE).orElseThrow().data());
        assertFalse(runtime.optional(UNSELECTED).isPresent());
        runtime.start();
        assertEquals(RuntimeState.RUNNING, runtime.runtimeState());
        assertEquals(HealthStatus.HEALTHY, runtime.healthSnapshot().components().getFirst().result().status());
        runtime.stop();

        assertEquals(List.of(
                "create:data", "create:service",
                "start:data", "start:service", "health:service",
                "stop:service", "stop:data",
                "close:service", "close:data"), events);
        assertEquals(RuntimeState.STOPPED, runtime.runtimeState());
        assertEquals(0, runtime.report().pendingResourceCloseCount());
        assertTrue(runtime.report().components().stream()
                .allMatch(status -> status.create() == RuntimePhaseOutcome.SUCCEEDED));
        runtime.close();
        assertEquals(RuntimeState.CLOSED, runtime.runtimeState());
        assertEquals(9, events.size());
    }

    @Test
    void providerSelectedForTwoKeysShouldCreateOnlyOnceAndCommitAtomically() {
        ComponentId combinedId = ComponentId.of("runtime.provider.combined");
        ComponentKey<String> first = ComponentKey.single("runtime.combined.first", String.class);
        ComponentKey<Integer> second = ComponentKey.single("runtime.combined.second", Integer.class);
        ComponentDescriptor descriptor = ComponentDescriptor.builder(combinedId)
                .provide(first)
                .provide(second)
                .build();
        Object shared = new Object();
        FakeRuntimeProvider provider = new FakeRuntimeProvider(descriptor, context -> ComponentContribution.builder()
                .bind(first, shared.toString())
                .bind(second, 42)
                .build());
        ComponentCatalog catalog = ComponentCatalog.builder().register("test", provider).build();

        GameRuntime runtime = RuntimeAssembler.builder(catalog, RuntimeProfile.local())
                .require(first)
                .require(second)
                .select(first, combinedId, "test")
                .select(second, combinedId, "test")
                .build();

        assertEquals(1, provider.createCount());
        assertEquals(42, runtime.require(second));
        assertSame(runtime.require(first), runtime.require(first));
        runtime.close();
    }

    private static FakeRuntimeProvider dataProvider(
            final List<String> events,
            final RecordingLifecycle lifecycle) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(DATA_ID)
                .provide(DATA)
                .build();
        return new FakeRuntimeProvider(descriptor, context -> {
            events.add("create:data");
            context.resources().register(RecordingResource.healthy("data", events));
            return ComponentContribution.builder()
                    .bind(DATA, "data-value")
                    .lifecycle(lifecycle)
                    .build();
        });
    }

    private static FakeRuntimeProvider serviceProvider(
            final List<String> events,
            final RecordingLifecycle lifecycle,
            final HealthResult healthResult) {
        ComponentDescriptor descriptor = ComponentDescriptor.builder(SERVICE_ID)
                .provide(SERVICE)
                .require(DATA)
                .health(HealthPhase.STARTUP)
                .build();
        return new FakeRuntimeProvider(descriptor, context -> {
            events.add("create:service");
            Service service = new Service(context.require(DATA));
            context.resources().register(RecordingResource.healthy("service", events));
            return ComponentContribution.builder()
                    .bind(SERVICE, service)
                    .lifecycle(lifecycle)
                    .healthProbe(HealthPhase.STARTUP, request -> {
                        events.add("health:service");
                        return CompletableFuture.completedFuture(healthResult);
                    })
                    .build();
        });
    }

    private record Service(String data) {
    }
}
