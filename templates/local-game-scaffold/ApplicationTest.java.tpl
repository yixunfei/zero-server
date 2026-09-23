package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local game scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedProtocolFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local game scaffold result",
                () -> assertEquals("__RUNTIME_PROFILE__", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals(1001L, result.uid()),
                () -> assertEquals(3, result.position().x()),
                () -> assertEquals(5, result.position().y()),
                () -> assertEquals(4, result.logCount()),
                () -> assertEquals(2, result.metricCount()),
                () -> assertEquals(90105, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("local-game=ok|mode=__RUNTIME_PROFILE__|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=90105")));
    }
}
