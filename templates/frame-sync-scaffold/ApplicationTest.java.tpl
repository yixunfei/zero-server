package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local frame sync scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedFrameSyncFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local frame sync scaffold result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals("match=match-1,players=2,frame=2,inputs=3,inputSum=17,snapshots=2",
                        result.snapshotSummary()),
                () -> assertEquals(9, result.logCount()),
                () -> assertEquals(8, result.metricCount()),
                () -> assertEquals(93107, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("frame-sync=ok|mode=local|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=93107")));
    }
}
