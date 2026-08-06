package group.zn.zero.examples.rpg;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * RPG 协议驱动本地示例回归测试。
 *
 * @author zn
 */
class RpgProtocolApplicationTest {

    @Test
    void runDemoShouldCompleteGeneratedProtocolFlow() {
        RpgProtocolApplication.ProtocolDemoResult result = RpgProtocolApplication.runDemo();
        String summary = result.summaryLine();

        assertAll("rpg protocol demo result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("rpg-protocol", result.name()),
                () -> assertEquals(1001L, result.uid()),
                () -> assertEquals("player-1001", result.playerName()),
                () -> assertEquals(7, result.position().x()),
                () -> assertEquals(11, result.position().y()),
                () -> assertEquals("scene=scene-1|entities=1|first=1001@7,11", result.sceneBeforeLeave()),
                () -> assertEquals("scene=scene-1|entities=0", result.sceneAfterLeave()),
                () -> assertTrue(result.leaveRemoved()),
                () -> assertTrue(result.logCount() >= 7),
                () -> assertEquals(7, result.metricCount()),
                () -> assertEquals(80113, result.maxProtocolId()),
                () -> assertTrue(summary.startsWith("rpg-protocol=ok|mode=local|name=rpg-protocol")),
                () -> assertTrue(summary.contains("|position=7,11|")),
                () -> assertTrue(summary.endsWith("|maxProtocolId=80113")));
    }
}
