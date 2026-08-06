package __PACKAGE__;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Local world shard scaffold regression test.
 *
 * @author zn
 */
class __TEST_CLASS__ {

    @Test
    void runDemoShouldCompleteGeneratedWorldShardFlow() {
        __APP_CLASS__.DemoResult result = __APP_CLASS__.runDemo();
        String summary = result.summaryLine();

        assertAll("local world shard scaffold result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("__PROJECT_NAME__", result.name()),
                () -> assertEquals("world=world-1,shards=2,entities=2,entity=1001,shard=shard-b,"
                                + "position=54,4,migrations=1,lastAction=query:1001@shard-b",
                        result.worldSummary()),
                () -> assertEquals(7, result.logCount()),
                () -> assertEquals(6, result.metricCount()),
                () -> assertEquals(96107, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("world-shard=ok|mode=local|name=__PROJECT_NAME__")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=96107")));
    }
}
