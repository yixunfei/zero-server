package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local room scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedRoomFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local room scaffold result",
                () -> assertEquals("__RUNTIME_PROFILE__", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals("room=room-1,owner=1001,players=2,ready=2,started=true,frame=1,inputSum=42",
                        result.roomSummary()),
                () -> assertEquals(7, result.logCount()),
                () -> assertEquals(6, result.metricCount()),
                () -> assertEquals(91109, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("room-game=ok|mode=__RUNTIME_PROFILE__|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=91109")));
    }
}
