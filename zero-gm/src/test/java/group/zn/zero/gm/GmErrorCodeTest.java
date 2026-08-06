package group.zn.zero.gm;

import static org.junit.jupiter.api.Assertions.assertEquals;

import group.zn.zero.core.error.ErrorCategory;
import org.junit.jupiter.api.Test;

/**
 * GM O1 新增稳定 ErrorCode 契约测试。
 *
 * @author zn
 */
class GmErrorCodeTest {

    /**
     * 验证新增三枚错误码的分类、稳定字符串和默认说明。
     */
    @Test
    void newErrorCodesShouldMatchFrozenContract() {
        assertCode(
                GmErrorCode.COMMAND_RESULT_INVALID,
                ErrorCategory.SYSTEM,
                "ZERO-GM-COMMAND-RESULT-INVALID",
                "gm command result is invalid");
        assertCode(
                GmErrorCode.COMMAND_REJECTED,
                ErrorCategory.CLIENT_REQUEST,
                "ZERO-GM-COMMAND-REJECTED",
                "gm command was rejected");
        assertCode(
                GmErrorCode.HANDLER_FAILED,
                ErrorCategory.SYSTEM,
                "ZERO-GM-HANDLER-FAILED",
                "gm command handler failed");
    }

    private void assertCode(
            final GmErrorCode errorCode,
            final ErrorCategory category,
            final String code,
            final String message) {
        assertEquals(category, errorCode.category());
        assertEquals(code, errorCode.code());
        assertEquals(message, errorCode.message());
    }
}
