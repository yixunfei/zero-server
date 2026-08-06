package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import group.zn.zero.core.error.SystemErrorCode;
import group.zn.zero.core.error.ZeroException;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * GM 执行结果 ErrorCode 组合测试。
 *
 * @author zn
 */
class GmCommandResultTest {

    /**
     * 验证成功结果不能携带 ErrorCode，拒绝结果不能缺少 ErrorCode。
     */
    @Test
    void resultErrorCodeMatrixShouldRejectConflicts() {
        ZeroException dryRunSuccessWithCode = assertThrows(
                ZeroException.class,
                () -> new GmDryRunResult("mail send", true, "ok", Map.of(), GmErrorCode.COMMAND_REJECTED));
        ZeroException dryRunRejectedWithoutCode = assertThrows(
                ZeroException.class,
                () -> new GmDryRunResult("mail send", false, "rejected", Map.of(), null));
        ZeroException executeSuccessWithCode = assertThrows(
                ZeroException.class,
                () -> new GmCommandExecutionResult(
                        "mail send",
                        false,
                        true,
                        "ok",
                        Map.of(),
                        GmErrorCode.COMMAND_REJECTED));
        ZeroException executeRejectedWithoutCode = assertThrows(
                ZeroException.class,
                () -> new GmCommandExecutionResult("mail send", false, false, "rejected", Map.of(), null));
        ZeroException dryRunRejectedWithOk = assertThrows(
                ZeroException.class,
                () -> new GmDryRunResult("mail send", false, "rejected", Map.of(), SystemErrorCode.OK));
        ZeroException executeRejectedWithOk = assertThrows(
                ZeroException.class,
                () -> new GmCommandExecutionResult(
                        "mail send",
                        false,
                        false,
                        "rejected",
                        Map.of(),
                        SystemErrorCode.OK));

        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, dryRunSuccessWithCode.errorCode());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, dryRunRejectedWithoutCode.errorCode());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, executeSuccessWithCode.errorCode());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, executeRejectedWithoutCode.errorCode());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, dryRunRejectedWithOk.errorCode());
        assertEquals(GmErrorCode.COMMAND_RESULT_INVALID, executeRejectedWithOk.errorCode());
    }

    /**
     * 验证便捷工厂使用真实拒绝码，成功不伪造 ZERO-OK。
     */
    @Test
    void factoriesShouldUseRealRejectionCodeAndNoSuccessCode() {
        GmDryRunResult accepted = GmDryRunResult.accepted("mail send", "ok", Map.of());
        GmDryRunResult rejected = GmDryRunResult.rejected("mail send", "denied", Map.of());
        GmCommandExecutionResult success = GmCommandExecutionResult.success("mail send", "ok", Map.of());
        GmCommandExecutionResult rejectedExecution = GmCommandExecutionResult.rejected(
                "mail send",
                "denied",
                Map.of(),
                GmErrorCode.COMMAND_ARGUMENT_MISMATCH);

        assertTrue(accepted.accepted());
        assertNull(accepted.errorCode());
        assertFalse(rejected.accepted());
        assertEquals(GmErrorCode.COMMAND_REJECTED, rejected.errorCode());
        assertTrue(success.success());
        assertNull(success.errorCode());
        assertFalse(rejectedExecution.success());
        assertEquals(GmErrorCode.COMMAND_ARGUMENT_MISMATCH, rejectedExecution.errorCode());
    }

    /**
     * 验证 dry-run 统一结果保留拒绝 ErrorCode。
     */
    @Test
    void dryRunConversionShouldPreserveErrorCode() {
        GmDryRunResult dryRun = GmDryRunResult.rejected(
                "mail send",
                "denied",
                Map.of(),
                GmErrorCode.COMMAND_ARGUMENT_MISMATCH);

        GmCommandExecutionResult result = GmCommandExecutionResult.fromDryRun(dryRun);

        assertTrue(result.dryRun());
        assertFalse(result.success());
        assertEquals(GmErrorCode.COMMAND_ARGUMENT_MISMATCH, result.errorCode());
    }
}
