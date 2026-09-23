package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local NPC tick scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedNpcTickFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local NPC tick scaffold result",
                () -> assertEquals("__RUNTIME_PROFILE__", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals("zone=zone-1,npcs=1,npc=2001,behavior=idle,position=12,10,"
                                + "tick=3,updates=7,lastAction=query:2001@12,10",
                        result.npcSummary()),
                () -> assertEquals(8, result.logCount()),
                () -> assertEquals(7, result.metricCount()),
                () -> assertEquals(94107, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("npc-tick=ok|mode=__RUNTIME_PROFILE__|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=94107")));
    }
}
