package group.zn.zero.runtime.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.core.error.ZeroException;
import group.zn.zero.runtime.bootstrap.RuntimeBasics;
import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeComponentProvider;
import group.zn.zero.runtime.spi.RuntimeProviders;
import group.zn.zero.runtime.bootstrap.ZeroRuntimeExecutors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProductionAssemblyTest {
    private static final ComponentKey<Runnable> SERVICE = ComponentKey.single("test.service", Runnable.class);

    @Test
    void independentlyRegisteredProviderRollsBackAndSanitizesFailures() {
        AtomicInteger closed = new AtomicInteger();
        RuntimeComponentProvider provider = RuntimeProviders.create(
                ComponentDescriptor.builder(ComponentId.of("test.provider")).provide(SERVICE).build(), context -> {
                    context.resources().register(() -> closed.incrementAndGet());
                    throw new IllegalStateException("private-connection-detail");
                });
        ProductionAssembly assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null)
                .install(context -> new ProductionModule(List.of(provider), List.of(), List.of()));
        assembly.diagnose();
        assertEquals(3, assembly.plan().components().size());
        assertEquals(0, closed.get());
        ProductionAdapterException failure = assertThrows(ProductionAdapterException.class, assembly::build);
        assertEquals(1, closed.get());
        assertFalse(failure.toString().contains("private-connection-detail"));
        assertThrows(IllegalStateException.class, assembly::build);
    }

    @Test
    void independentAssemblySetsModeAndRejectsMismatchedProfiles() {
        try (var runtime = ProductionAssembly.builder(new MapZeroConfig(Map.of())).build()) {
            assertEquals("production", runtime.require(RuntimeBasics.CONFIG).getOrDefault("zero.mode", "missing"));
        }
        assertThrows(ZeroException.class,
                () -> ProductionAssembly.builder(new MapZeroConfig(Map.of("zero.mode", "local"))));
        for (String profile : List.of("standalone", "external-test", "production")) {
            var assembly = ProductionAssembly.builder(profile, new MapZeroConfig(Map.of()));
            assertEquals(profile, assembly.diagnose().profile());
            assertEquals(2, assembly.plan().components().size());
            try (var runtime = assembly.build()) {
                runtime.start();
                assertEquals(profile, runtime.require(RuntimeBasics.CONFIG).getOrDefault("zero.mode", "missing"));
            }
        }
    }

    @Test
    void diagnosisSanitizesApplicationCustomizationFailures() {
        var assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of()))
                .configure(composition -> { throw new IllegalArgumentException("private-setting"); });
        var failure = assertThrows(ProductionAdapterException.class, assembly::diagnose);
        assertEquals(ProductionAdapterFailurePhase.CONFIG_VALIDATION, failure.failurePhase());
        assertFalse(failure.toString().contains("private-setting"));
        assertThrows(ProductionAdapterException.class, assembly::plan);
    }

    @Test
    void configurationFailureLeavesCallerExecutorsOwnedByCaller() {
        try (ZeroRuntimeExecutors executors = ZeroRuntimeExecutors.localPrototype("production-config-failure", 1)) {
            ProductionAssembly assembly = ProductionAssembly.builder("production", new MapZeroConfig(Map.of(
                            ZeroProductionRuntimeConfigKeys.ADAPTER_STARTUP_BUDGET_MILLIS, "invalid")), executors)
                    .configSourceLookups(key -> null, key -> null);
            assertThrows(ProductionAdapterException.class, assembly::build);
            assertFalse(((ExecutorService) executors.actorExecutor()).isShutdown());
        }
    }

    @Test
    void conflictingProductionModulesCannotSilentlyReplaceEachOther() {
        AtomicInteger created = new AtomicInteger();
        ProductionAssembly assembly = ProductionAssembly.builder(new MapZeroConfig(Map.of()))
                .configSourceLookups(key -> null, key -> null)
                .install(module("test.first", created)).install(module("test.second", created));
        ProductionAdapterException failure = assertThrows(ProductionAdapterException.class, assembly::build);
        assertEquals(ProductionAdapterFailurePhase.CONFIG_SELECTION, failure.failurePhase());
        assertEquals(0, created.get());
    }

    private ProductionModuleFactory module(final String id, final AtomicInteger created) {
        return context -> new ProductionModule(List.of(RuntimeProviders.create(
                ComponentDescriptor.builder(ComponentId.of(id)).provide(SERVICE).build(), creation -> {
                    created.incrementAndGet();
                    throw new IllegalStateException("must not create conflicting providers");
                })), List.of(), List.of());
    }
}
