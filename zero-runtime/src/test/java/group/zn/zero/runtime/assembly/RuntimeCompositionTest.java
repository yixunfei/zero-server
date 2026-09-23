package group.zn.zero.runtime.assembly;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.runtime.api.ComponentId;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.api.GameRuntime;
import group.zn.zero.runtime.diagnostics.RuntimeAssemblyException;
import group.zn.zero.runtime.diagnostics.RuntimeErrorCode;
import group.zn.zero.runtime.spi.ComponentContribution;
import group.zn.zero.runtime.spi.ComponentDescriptor;
import group.zn.zero.runtime.spi.RuntimeProviders;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.List;
import org.junit.jupiter.api.Test;

class RuntimeCompositionTest {
    private static final ComponentKey<String> VALUE = ComponentKey.single("test.value", String.class);
    private static final ComponentKey<String> CONSUMER = ComponentKey.single("test.consumer", String.class);

    @Test
    void unselectedFactoriesStayDormantAndConsumersReceiveReplacement() {
        AtomicInteger creations = new AtomicInteger();
        var original = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("test.original"))
                .provide(VALUE).build(), context -> {
                    creations.incrementAndGet();
                    return ComponentContribution.builder().bind(VALUE, "original").build();
                });
        var consumer = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("test.consumer"))
                .provide(CONSUMER).require(VALUE).build(), context ->
                ComponentContribution.builder().bind(CONSUMER, context.require(VALUE)).build());
        RuntimeComposition composition = RuntimeComposition.builder(RuntimeProfile.local())
                .install(RuntimeModule.of("test.module", List.of(original, consumer), CONSUMER))
                .replace(VALUE, "replacement");
        assertEquals(0, creations.get());
        composition.diagnose();
        assertEquals(0, creations.get());
        try (GameRuntime runtime = composition.build()) {
            runtime.start();
            assertEquals("replacement", runtime.require(CONSUMER));
            assertEquals(0, creations.get());
        }
        assertThrows(IllegalStateException.class, composition::build);
        assertThrows(IllegalStateException.class, () -> composition.replace(VALUE, "late"));
    }

    @Test
    void duplicateModulesFailBeforeFactoriesRun() {
        AtomicInteger creations = new AtomicInteger();
        var provider = RuntimeProviders.create(ComponentDescriptor.builder(ComponentId.of("test.value"))
                .provide(VALUE).build(), context -> {
                    creations.incrementAndGet();
                    return ComponentContribution.builder().bind(VALUE, "value").build();
                });
        RuntimeModule module = RuntimeModule.of("test.module", List.of(provider), VALUE);
        RuntimeComposition composition = RuntimeComposition.builder(RuntimeProfile.local()).install(module).install(module);
        RuntimeAssemblyException failure = assertThrows(RuntimeAssemblyException.class, composition::build);
        assertSame(RuntimeErrorCode.RUNTIME_DUPLICATE_PROVIDER, failure.errorCode());
        assertEquals(0, creations.get());
    }
}
