package group.zn.zero.examples.framesync;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import group.zn.zero.framesync.FrameInput;
import org.junit.jupiter.api.Test;

/** 验证输入真实进入模拟及通知链，重复提交不重复消费。 @author zn */
class FrameSyncDemoTest {
    /** 同一输入消费一次，提交事件和广播共享正确 tick 追踪标识。 */
    @Test
    void commitsInputThroughSimulationAndNotifications() {
        var result = FrameSyncDemo.runFlow();
        assertEquals(1, result.frameNo());
        assertEquals(1, result.batches().size());
        assertEquals(1, result.batches().getFirst().frameNo());
        assertEquals(1, result.batches().getFirst().inputs().size());
        FrameInput input = result.batches().getFirst().inputs().getFirst();
        assertEquals("player-1", input.uid());
        assertEquals(1, input.inputSeq());
        assertEquals(1, input.targetFrame());
        assertArrayEquals(new byte[] {1}, input.payload());
        assertEquals(1, result.events().size());
        assertEquals("trace-tick", result.events().getFirst().traceId());
        assertEquals(result.batches().getFirst(), result.events().getFirst().batch());
        assertEquals(result.events(), result.broadcasts());
    }
}
