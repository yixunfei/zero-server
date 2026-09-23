package group.zn.zero.framesync;

/** Committed frame notification. */
public record FrameCommitted(String matchId, long frameNo, FrameInputBatch batch, String traceId) { }
