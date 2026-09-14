package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** 验证本地生命周期或外部诊断入口，以及本地选择图。 */
class __TEST_CLASS__ {
    @Test
    void selectedCompositionRunsWithoutImplicitExternalConnections() {
        String summary = __APP_CLASS__.runDemo(__DEFAULT_START__);
        assertTrue(summary.endsWith("started=__DEFAULT_START__"));
        assertFalse(summary.contains("componentTypes="));
        assertFalse(summary.contains("group.zn.zero."));
    }

    @Test
    void generatedSelectionMatchesTheInstalledModules() {
        if (__EXTERNAL_COMPONENTS__) {
            // 外部测试环境由应用提供；普通 test 不创建真实客户端。
            assertNotNull(RuntimeAssembly.diagnose(__APP_CLASS__.configuration()));
        } else {
            var plan = RuntimeAssembly.plan(__APP_CLASS__.configuration());
            assertEquals(Set.of(
__SELECTED_PROVIDERS_JSON__
            ), plan.components().stream().map(component -> component.componentId().value())
                    .collect(Collectors.toSet()));
            assertEquals(Set.of(
__RUNTIME_CAPABILITIES_JSON__
            ), plan.components().stream().flatMap(component -> component.provides().stream())
                    .map(key -> key.id()).collect(Collectors.toSet()));
        }
    }
}
