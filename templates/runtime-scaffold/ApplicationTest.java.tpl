package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class __TEST_CLASS__ {
    @Test
    void selectedCompositionBuildsAndCloses() {
        assertTrue(__APP_CLASS__.runDemo(__DEFAULT_START__).endsWith("started=__DEFAULT_START__"));
    }

    @Test
    void generatedSelectionMatchesTheInstalledModules() {
        try (var runtime = RuntimeAssembly.create(__APP_CLASS__.configuration())) {
            assertEquals(Set.of(
__SELECTED_PROVIDERS_JSON__
            ), runtime.plan().components().stream().map(component -> component.componentId().value())
                    .collect(Collectors.toSet()));
            assertEquals(Set.of(
__RUNTIME_CAPABILITIES_JSON__
            ), runtime.plan().components().stream().flatMap(component -> component.provides().stream())
                    .map(key -> key.id()).collect(Collectors.toSet()));
        }
    }
}
