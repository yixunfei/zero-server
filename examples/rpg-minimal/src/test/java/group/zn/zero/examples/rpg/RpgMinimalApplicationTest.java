package group.zn.zero.examples.rpg;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * RPG 最小本地示例回归测试。
 *
 * @author zn
 */
class RpgMinimalApplicationTest {

    @Test
    void runDemoShouldCompleteLocalRpgFlow() {
        RpgMinimalApplication.DemoResult result = RpgMinimalApplication.runDemo();
        String summary = result.summaryLine();

        assertAll("rpg minimal demo result",
                () -> assertEquals("local", result.mode()),
                () -> assertEquals("rpg-minimal", result.name()),
                () -> assertEquals(1001L, result.uid()),
                () -> assertEquals("player-1001", result.playerName()),
                () -> assertEquals(7, result.position().x()),
                () -> assertEquals(11, result.position().y()),
                () -> assertEquals(1, result.sceneBeforeLeave()),
                () -> assertEquals(0, result.sceneAfterLeave()),
                () -> assertTrue(result.leaveRemoved()),
                () -> assertTrue(result.logCount() >= 5),
                () -> assertEquals(5, result.metricCount()),
                () -> assertTrue(summary.startsWith("rpg-minimal=ok|mode=local|name=rpg-minimal")),
                () -> assertTrue(summary.contains("|position=7,11|")),
                () -> assertTrue(summary.endsWith("|metrics=5")));
    }
}
