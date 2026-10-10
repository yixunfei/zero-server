package group.zn.zero.net.kcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

/** 分片跟踪使用序号及 frg，覆盖乱序、重复、回绕和有界静默拒绝。 @author zn */
class KcpReceiveTrackerTest {
    @Test void reorderDuplicateAndExactFragmentCountdownAreRequired() {
        var tracker = new KcpReceiveTracker(32);
        assertNull(accept(tracker, 1, 0, 3, false));
        assertFalse(tracker.empty());
        assertEquals(0, tracker.messageSegments(5));
        assertNull(accept(tracker, 0, 1, 2, false));
        assertNull(accept(tracker, 0, 1, 2, false));
        assertEquals("receive.segment_conflict", accept(tracker, 0, 0, 2, false));
        assertEquals(0, tracker.messageSegments(6));
        assertEquals(2, tracker.messageSegments(5));
        tracker.delivered(2);
        assertTrue(tracker.empty());
        assertNull(accept(tracker, 0, 1, 2, true));
        assertEquals("receive.quiescent_push", accept(tracker, 2, 0, 2, true));
    }

    @Test void windowBoundAndUndeliveredQuietRetransmissionAreRejected() {
        var tracker = new KcpReceiveTracker(32);
        for (int i = 0; i < 64; i++) assertNull(accept(tracker, i, 0, 1, false));
        assertEquals("receive.window_exceeded", accept(tracker, 64, 0, 1, false));
        assertEquals("receive.quiescent_push", accept(tracker, 0, 0, 1, true));
        assertFalse(tracker.empty());
        tracker.clear();
        assertTrue(tracker.empty());
    }

    @Test void invalidFragmentSequenceCannotMerge() {
        var tracker = new KcpReceiveTracker(32);
        assertNull(accept(tracker, 0, 1, 1, false));
        assertNull(accept(tracker, 1, 1, 1, false));
        assertEquals(0, tracker.messageSegments(2));
        assertEquals("receive.window_exceeded", accept(new KcpReceiveTracker(32), -1, 0, 1, false));
    }

    @Test void unsignedWrapPreservesDeliveredWatermark() throws Exception {
        var tracker = new KcpReceiveTracker(32);
        // 仅测试跨越 2^32 的状态，不循环发送四十亿个段；生产不反射算法。
        var expected = KcpReceiveTracker.class.getDeclaredField("expected");
        expected.setAccessible(true);
        expected.setInt(tracker, -1);
        assertNull(accept(tracker, 0, 0, 2, false));
        assertNull(accept(tracker, -1, 1, 3, false));
        assertEquals(2, tracker.messageSegments(5));
        tracker.delivered(2);
        assertNull(accept(tracker, -1, 1, 3, true));
        assertNull(accept(tracker, 0, 0, 2, true));
        assertEquals("receive.quiescent_push", accept(tracker, 1, 0, 1, true));
    }

    private static String accept(final KcpReceiveTracker tracker, final int sequence, final int fragment,
            final int length, final boolean quiet) {
        ByteBuf body = segment(1, sequence, fragment, new byte[length]);
        try { return tracker.accept(body, quiet); }
        finally { body.release(); }
    }

    static ByteBuf segment(final int conv, final int sequence, final int fragment, final byte[] bytes) {
        return Unpooled.buffer(24 + bytes.length).writeIntLE(conv).writeByte(81).writeByte(fragment)
                .writeShortLE(128).writeIntLE(0).writeIntLE(sequence).writeIntLE(0).writeIntLE(bytes.length).writeBytes(bytes);
    }
}
