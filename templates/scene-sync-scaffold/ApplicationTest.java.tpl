package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local scene sync scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedSceneSyncFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local scene sync scaffold result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals(
                        "scene=scene-1,entities=3,focus=1001,visible=2,visibleIds=1002,1003,lastDelta=move:1003@8,8",
                        result.sceneSummary()),
                () -> assertEquals(6, result.logCount()),
                () -> assertEquals(5, result.metricCount()),
                () -> assertEquals(92105, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("scene-sync=ok|mode=local|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=92105")));
    }
}
