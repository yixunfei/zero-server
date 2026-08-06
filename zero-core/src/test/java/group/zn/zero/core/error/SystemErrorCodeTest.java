package group.zn.zero.core.error;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

/**
 * 系统错误码测试。
 *
 * @author zn
 */
class SystemErrorCodeTest {

    /**
     * 验证成功错误码保持稳定。
     */
    @Test
    void okCodeShouldBeStable() {
        assertEquals("ZERO-OK", SystemErrorCode.OK.code());
    }

    /**
     * 验证统一异常说明不可为空。
     */
    @Test
    void zeroExceptionShouldRequireMessage() {
        assertThrows(NullPointerException.class, () -> new ZeroException(SystemErrorCode.SYSTEM_ERROR, null));
    }
}
