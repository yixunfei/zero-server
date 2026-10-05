package group.zn.zero.framesync;

import java.util.Objects;

/** Capacity and timing policy for a frame match. */
public record FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                               int maxPayloadBytes, int maxBufferedInputs, int maxFutureFrames) {
    /** 保留原四参数构造器，使用默认提前窗口。 */
    public FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                            int maxPayloadBytes, int maxBufferedInputs) {
        this(inputTimingPolicy, missingInputPolicy, maxPayloadBytes, maxBufferedInputs, 120);
    }

    public FrameMatchConfig {
        Objects.requireNonNull(inputTimingPolicy, "inputTimingPolicy");
        Objects.requireNonNull(missingInputPolicy, "missingInputPolicy");
        if (maxPayloadBytes <= 0 || maxBufferedInputs <= 0 || maxFutureFrames <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }
    public static FrameMatchConfig defaults() {
        return new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 4096, 4096, 120);
    }
}
