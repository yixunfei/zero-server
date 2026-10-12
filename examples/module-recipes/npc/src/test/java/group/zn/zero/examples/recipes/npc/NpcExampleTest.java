package group.zn.zero.examples.recipes.npc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.npc.NpcErrorCode;
import group.zn.zero.npc.TickResult;
import org.junit.jupiter.api.Test;

/** 正式 NPC 的预算、错误隔离及生命周期回归。 @author zn */
class NpcExampleTest {
    /** 次数预算可复现，行为失败不会阻断其他 NPC。 @throws Exception 异步处理失败。 */
    @Test
    void boundsDecisionsReportsFailureAndRemovesNpc() throws Exception {
        var result = NpcExample.runFlow();
        assertEquals(1, result.limited().processedCount());
        assertEquals(2, result.limited().skippedCount());
        assertEquals(TickResult.Outcome.DEGRADED, result.limited().outcome());
        assertEquals(3, result.complete().processedCount());
        assertEquals(TickResult.Outcome.COMPLETED, result.complete().outcome());
        assertEquals(NpcErrorCode.ALREADY_EXISTS.code(), result.duplicateCode());
        assertEquals(1, result.behaviorFailures());
        assertTrue(result.patrol());
        assertTrue(result.removed());
        assertEquals(2, result.remaining());
        assertEquals(4, result.audits());
    }
}
