package group.zn.zero.framesync;

import java.util.Objects;

/** Capacity and timing policy for a frame match. */
public record FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                               int maxPayloadBytes, int maxBufferedInputs, int maxFutureFrames, int maxParticipants) {
    /** 保留原四参数构造器，使用默认提前窗口。 */
    public FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                            int maxPayloadBytes, int maxBufferedInputs) {
        this(inputTimingPolicy, missingInputPolicy, maxPayloadBytes, maxBufferedInputs, 120);
    }

    /** 创建默认 4096 参与者容量的配置；输入队列容量独立计算。
     * @param inputTimingPolicy 输入时序策略。 @param missingInputPolicy 缺帧策略。
     * @param maxPayloadBytes 单输入字节。 @param maxBufferedInputs 排队输入数。
     * @param maxFutureFrames 提前帧窗口。
     */
    public FrameMatchConfig(InputTimingPolicy inputTimingPolicy, MissingInputPolicy missingInputPolicy,
                            int maxPayloadBytes, int maxBufferedInputs, int maxFutureFrames) {
        this(inputTimingPolicy, missingInputPolicy, maxPayloadBytes, maxBufferedInputs, maxFutureFrames, 4096);
    }

    public FrameMatchConfig {
        Objects.requireNonNull(inputTimingPolicy, "inputTimingPolicy");
        Objects.requireNonNull(missingInputPolicy, "missingInputPolicy");
        if (maxPayloadBytes <= 0 || maxBufferedInputs <= 0 || maxFutureFrames <= 0 || maxParticipants <= 0) {
            throw new IllegalArgumentException("capacity must be positive");
        }
    }
    public static FrameMatchConfig defaults() {
        return new FrameMatchConfig(InputTimingPolicy.REJECT, MissingInputPolicy.EMPTY, 4096, 4096, 120);
    }
}
