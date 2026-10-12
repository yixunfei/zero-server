package group.zn.zero.examples.recipes.events;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import group.zn.zero.core.error.SystemErrorCode;
import java.util.List;
import org.junit.jupiter.api.Test;

/** 事件到 Actor 的真实状态与失败恢复回归。 @author zn */
class EventActorExampleTest {
    /** 失败输入不得污染余额，后续消息仍能处理。 @throws Exception 异步操作失败。 */
    @Test
    void preservesTraceAndStateAfterRejectedCommand() throws Exception {
        var result = EventActorExample.runFlow();
        assertEquals(30, result.points());
        assertEquals(List.of("trace-1", "trace-2"), result.traces());
        assertEquals(SystemErrorCode.INVALID_ARGUMENT.code(), result.rejectionCode());
        assertThrows(UnsupportedOperationException.class, () -> result.traces().add("mutated"));
    }
}
