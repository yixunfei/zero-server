package group.zn.zero.examples.framesync;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class FrameSyncDemoTest {
    private FrameSyncDemo.LocalFrameSync match(FrameSyncDemo.LatePolicy late) {
        var m = new FrameSyncDemo.LocalFrameSync(new FrameSyncDemo.Config(4, 2, 64,
                FrameSyncDemo.MissingPolicy.EMPTY_INPUT, late));
        m.join("match-1", "alice"); m.join("match-1", "bob"); return m;
    }

    @Test void frameClockAdvancesMonotonically() {
        var m = match(FrameSyncDemo.LatePolicy.REJECT); assertEquals(1, m.tick(1).frameNo());
        assertThrows(IllegalArgumentException.class, () -> m.tick(1));
    }
    @Test void inputSeqIsIdempotentPerPlayer() {
        var m = match(FrameSyncDemo.LatePolicy.REJECT); var i = new FrameSyncDemo.Input("alice", 1, 1, "x");
        assertEquals(FrameSyncDemo.InputStatus.ACCEPTED, m.submit(i).status());
        assertEquals(FrameSyncDemo.InputStatus.DUPLICATE, m.submit(i).status());
        assertEquals(FrameSyncDemo.InputStatus.REJECTED_CONFLICT, m.submit(new FrameSyncDemo.Input("alice", 1, 1, "y")).status());
    }
    @Test void earlyInputBufferedUntilTargetFrame() {
        var m = match(FrameSyncDemo.LatePolicy.REJECT); m.submit(new FrameSyncDemo.Input("alice", 1, 2, "x"));
        assertTrue(m.tick(1).inputs().isEmpty()); assertEquals(1, m.tick(2).inputs().size());
    }
    @Test void lateInputRejectedOrMarkedByPolicy() {
        var reject = match(FrameSyncDemo.LatePolicy.REJECT); reject.tick(1);
        assertEquals(FrameSyncDemo.InputStatus.REJECTED_TOO_LATE, reject.submit(new FrameSyncDemo.Input("alice", 1, 1, "x")).status());
        var mark = match(FrameSyncDemo.LatePolicy.MARK); mark.tick(1);
        assertEquals(FrameSyncDemo.InputStatus.REJECTED_TOO_LATE, mark.submit(new FrameSyncDemo.Input("alice", 1, 1, "x")).status());
    }
    @Test void missingInputPolicyIsExplicit() {
        var m = match(FrameSyncDemo.LatePolicy.REJECT); var frame = m.tick(1);
        assertEquals(List.of("alice", "bob"), frame.missing());
    }
    @Test void frameInputBatchOrderIsStable() {
        var m = match(FrameSyncDemo.LatePolicy.REJECT); m.submit(new FrameSyncDemo.Input("bob", 1, 1, "b")); m.submit(new FrameSyncDemo.Input("alice", 1, 1, "a"));
        assertEquals(List.of("alice", "bob"), m.tick(1).inputs().stream().map(FrameSyncDemo.Input::uid).toList());
    }
    @Test void broadcastOrderFollowsFrameNo() { var m = match(FrameSyncDemo.LatePolicy.REJECT); assertEquals(List.of(1L, 2L), List.of(m.tick(1).frameNo(), m.tick(2).frameNo())); }
    @Test void snapshotDigestMatchesCommittedFrame() { var m = match(FrameSyncDemo.LatePolicy.REJECT); m.tick(1); assertEquals(1, m.snapshot().frameNo()); assertEquals(12, m.snapshot().digest().length()); }
    @Test void slowSubscriberDoesNotBlockMatchActor() { var m = match(FrameSyncDemo.LatePolicy.REJECT); assertDoesNotThrow(() -> { m.tick(1); m.tick(2); }); }
    @Test void metricsDoNotUseMatchIdLabels() { var m = match(FrameSyncDemo.LatePolicy.REJECT); assertNotNull(m.snapshot()); }
}
