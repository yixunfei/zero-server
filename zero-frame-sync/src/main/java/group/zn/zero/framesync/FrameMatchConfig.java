package group.zn.zero.framesync;

import java.util.Objects;

/** Capacity and timing policy for a frame match. */
public record FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                               int maxPayloadBytes, int maxBufferedInputs) {
    public FrameMatchConfig {
        Objects.requireNonNull(inputTimingPolicy, "inputTimingPolicy");
        Objects.requireNonNull(missingInputPolicy, "missingInputPolicy");
        if (maxPayloadBytes <= 0 || maxBufferedInputs <= 0) throw new IllegalArgumentException("capacity must be positive");
    }
    public static FrameMatchConfig defaults() { return new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 4096, 4096); }
}
