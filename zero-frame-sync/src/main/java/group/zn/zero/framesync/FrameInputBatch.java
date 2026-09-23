package group.zn.zero.framesync;

import java.util.List;

/** Immutable inputs committed for one authoritative frame. */
public record FrameInputBatch(long frameNo, List<FrameInput> inputs) {
    public FrameInputBatch { inputs = List.copyOf(inputs); }
}
