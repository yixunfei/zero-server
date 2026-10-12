package group.zn.zero.examples.recipes.ownership;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import org.junit.jupiter.api.Test;

/** 租约与状态提交接入回归，拒绝操作不能改动权威积分。 @author zn */
class OwnershipExampleTest {
    /** 原请求只提交一次；交接后旧写入、旧重放、旧释放都无权生效。 */
    @Test
    void fencesOldOwnerAndDoesNotReplayStateChanges() {
        var result = OwnershipExample.runFlow();
        assertEquals(1, result.initialVersion());
        assertTrue(result.replayed());
        assertEquals(1, result.firstEpoch());
        assertEquals(2, result.secondEpoch());
        assertEquals(2, result.finalVersion());
        assertEquals(150, result.finalCoins());
        assertTrue(result.oldReleaseRejected());
        // 本地 ownership 当前使用统一错误码，不能将其误读为细分冲突原因。
        assertEquals(SystemErrorCode.SYSTEM_ERROR.code(), result.conflictCode());
        assertEquals(SystemErrorCode.SYSTEM_ERROR.code(), result.staleWriteCode());
        assertEquals(SystemErrorCode.SYSTEM_ERROR.code(), result.staleReplayCode());
    }
}
