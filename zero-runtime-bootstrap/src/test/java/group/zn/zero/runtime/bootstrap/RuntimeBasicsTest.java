package group.zn.zero.runtime.bootstrap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.config.MapZeroConfig;
import group.zn.zero.runtime.api.ComponentKey;
import group.zn.zero.runtime.assembly.RuntimeComposition;
import group.zn.zero.runtime.assembly.RuntimeProfile;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class RuntimeBasicsTest {
    @Test
    void executorFactoryIsDeferredUntilSuccessfulPlanning() {
        var created = new AtomicInteger();
        var assembly = RuntimeComposition.builder(RuntimeProfile.local()).install(RuntimeBasics.module(
                new MapZeroConfig(Map.of()), () -> {
                    created.incrementAndGet();
                    return ZeroRuntimeExecutors.direct();
                }));
        assembly.diagnose();
        assertEquals(0, created.get());
        try (var runtime = assembly.build()) {
            assertEquals(1, created.get());
            runtime.start();
        }
    }

    @Test
    void invalidCompositionDoesNotAllocateExecutors() {
        var created = new AtomicInteger();
        var assembly = RuntimeComposition.builder(RuntimeProfile.local()).install(RuntimeBasics.module(
                new MapZeroConfig(Map.of()), () -> {
                    created.incrementAndGet();
                    return ZeroRuntimeExecutors.direct();
                })).require(ComponentKey.single("application.missing", String.class));
        assertThrows(RuntimeException.class, assembly::build);
        assertEquals(0, created.get());
    }
}
