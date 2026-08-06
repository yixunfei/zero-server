package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local ranking season scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedRankingSeasonFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local ranking season scaffold result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals("season=season-2,players=1,top=1001:300,player=1001,"
                                + "rank=1,score=300,resets=1,lastAction=rank:1001=1",
                        result.rankingSummary()),
                () -> assertEquals(9, result.logCount()),
                () -> assertEquals(8, result.metricCount()),
                () -> assertEquals(95107, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("ranking-season=ok|mode=local|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=95107")));
    }
}
