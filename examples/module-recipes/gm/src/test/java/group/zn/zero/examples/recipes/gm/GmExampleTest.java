package group.zn.zero.examples.recipes.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.gm.GmAuditPhase;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 真实 REST→endpoint→executor 链路验证；不启动 HTTP 服务。 @author zn */
class GmExampleTest {
    /** 预演、重放与拒绝路径不重复修改状态，且产生对应审计。 */
    @Test
    void authorizesAuditsAndReplaysWithoutDuplicateExecution() {
        var result = GmExample.runFlow();
        assertEquals(200, result.previewStatus());
        assertEquals(0, result.afterPreview());
        assertEquals(200, result.executeStatus());
        assertEquals(200, result.replayStatus());
        assertEquals(409, result.conflictStatus());
        assertEquals(403, result.mismatchStatus());
        assertEquals(403, result.unapprovedStatus());
        assertEquals(403, result.forbiddenStatus());
        assertEquals(401, result.unauthenticatedStatus());
        assertEquals(1, result.executions());
        assertEquals(3, result.denials());
        assertEquals("COMMITTED", result.commitState());
        assertTrue(result.hiddenInternalField());
        assertEquals(List.of(GmAuditPhase.BEFORE_DRY_RUN, GmAuditPhase.AFTER_DRY_RUN,
                GmAuditPhase.BEFORE_EXECUTE, GmAuditPhase.AFTER_EXECUTE, GmAuditPhase.REPLAYED,
                GmAuditPhase.REJECTED, GmAuditPhase.REJECTED, GmAuditPhase.REJECTED,
                GmAuditPhase.REJECTED), result.phases());
    }
}
